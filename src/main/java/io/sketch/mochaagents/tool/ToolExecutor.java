// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool;

import io.sketch.mochaagents.event.AgentEvents;
import io.sketch.mochaagents.event.EventBus;
import io.sketch.mochaagents.interaction.ApprovalBroker;
import io.sketch.mochaagents.interaction.Decision;
import io.sketch.mochaagents.interaction.DecisionPipeline;
import io.sketch.mochaagents.interaction.PermissionRules;
import io.sketch.mochaagents.interaction.Session;
import io.sketch.mochaagents.interaction.ToolUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tool executor — Chain of Responsibility pipeline with batch/streaming strategies.
 *
 * <p>Implements {@link ToolExecutionStrategy} with built-in:
 * <ol>
 *   <li>Schema validation</li>
 *   <li>Permission check (pipeline or simple rules)</li>
 *   <li>Pre/post tool hooks</li>
 *   <li>Retry with timeout and backoff</li>
 *   <li>Output normalization and truncation</li>
 *   <li>Batch execution with concurrency-safe partitioning</li>
 * </ol>
 *
 * <pre>{@code
 * var executor = new ToolExecutor(registry)
 *     .withHooks(hooks)
 *     .withPermissions(rules, pipeline, broker)
 *     .withEvents(eventBus)
 *     .withMaxOutputChars(200_000);
 * }</pre>
 *
 * @author lanxia39@163.com
 */
public class ToolExecutor implements ToolExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry registry;
    private final long timeoutMs;
    private final int maxRetries;
    private final long retryDelayMs;

    private Hooks hooks;
    private PermissionRules permissionRules;
    private DecisionPipeline decisionPipeline;
    private ApprovalBroker approvalBroker;
    private Session session;
    private EventBus events;
    private String sessionId = "default";
    private int maxOutputChars = 500_000;

    public ToolExecutor(ToolRegistry registry) {
        this(registry, 60_000, 2, 500);
    }

    public ToolExecutor(ToolRegistry registry, long timeoutMs, int maxRetries, long retryDelayMs) {
        this.registry = registry;
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
    }

    // ── Configuration ──

    public ToolExecutor withHooks(Hooks h) { this.hooks = h; return this; }
    public ToolExecutor withPermissions(PermissionRules rules, DecisionPipeline pipeline, ApprovalBroker broker) {
        this.permissionRules = rules; this.decisionPipeline = pipeline; this.approvalBroker = broker; return this;
    }
    public ToolExecutor withPermissions(PermissionRules rules) { this.permissionRules = rules; return this; }
    public ToolExecutor withPipeline(DecisionPipeline p) { this.decisionPipeline = p; return this; }
    public ToolExecutor withBroker(ApprovalBroker b) { this.approvalBroker = b; return this; }
    public ToolExecutor withEvents(EventBus bus) { this.events = bus; return this; }
    public ToolExecutor withSession(Session s) { this.session = s; return this; }
    public ToolExecutor withSessionId(String id) { this.sessionId = id != null ? id : "default"; return this; }
    public ToolExecutor withMaxOutputChars(int n) { this.maxOutputChars = n; return this; }

    // ── ToolExecutionStrategy ──

    @Override
    public ToolResult execute(String toolName, Map<String, Object> arguments) {
        Tool tool = registry.get(toolName);
        if (tool == null) {
            log.warn("Tool '{}' not found", toolName);
            return ToolResult.Builder.failure(toolName, "Tool not found: " + toolName, null);
        }

        var validation = tool.validateInput(arguments);
        if (!validation.isValid()) {
            return ToolResult.Builder.failure(toolName, "Validation failed: " + validation.getMessage(), null);
        }

        ToolResult permissionResult = checkPermissions(toolName, arguments);
        if (permissionResult != null) return permissionResult;

        if (hooks != null) {
            var decision = hooks.applyPreTool(tool, arguments);
            if (decision.outcome() == Hooks.HookDecision.Outcome.DENY)
                return ToolResult.Builder.failure(toolName, "Hook denied: " + decision.reason(), null);
            if (decision.modifiedArgs() != null) arguments = decision.modifiedArgs();
        }

        final Map<String, Object> finalArgs = arguments;
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            try {
                long start = System.currentTimeMillis();
                CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> tool.call(finalArgs));
                Object result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                long elapsed = System.currentTimeMillis() - start;

                result = normalize(result, toolName);
                log.info("[Tool] {} ({}ms) args={}", toolName, elapsed, summarizeArgs(arguments));

                if (hooks != null) hooks.applyPostTool(tool, arguments, result, msg -> {});
                fireToolEvent(toolName, arguments, result, elapsed);

                return ToolResult.Builder.success(toolName, result, elapsed);

            } catch (TimeoutException e) {
                lastError = io.sketch.mochaagents.MochaException.ToolException.timeout(toolName);
                log.warn("Tool '{}' timeout (attempt {}/{})", toolName, attempt, maxRetries + 1);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IllegalArgumentException iae) throw iae;
                lastError = io.sketch.mochaagents.MochaException.ToolException.execution(toolName, e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = new RuntimeException("Tool '" + toolName + "' interrupted", e);
            } catch (Exception e) {
                lastError = io.sketch.mochaagents.MochaException.ToolException.execution(toolName, e.getMessage(), e);
            }

            if (attempt <= maxRetries) {
                try { Thread.sleep(retryDelayMs * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        log.error("Tool '{}' exhausted {} retries", toolName, maxRetries + 1);
        return ToolResult.Builder.failure(toolName, lastError.getMessage(), null);
    }

    @Override
    public List<ToolResult> executeBatch(List<ToolCall> calls) {
        if (calls.isEmpty()) return List.of();
        if (calls.size() == 1) return List.of(execute(calls.get(0).name(), calls.get(0).arguments()));

        var batches = partition(calls);
        var results = new ArrayList<ToolResult>();
        var abort = new AtomicBoolean(false);

        for (var batch : batches) {
            if (abort.get()) {
                for (var tc : batch) results.add(ToolResult.Builder.failure(tc.name(), "Aborted: sibling error", null));
                continue;
            }
            if (batch.size() > 1 && isConcurrencySafe(batch.get(0).name())) {
                executeParallel(batch, results, abort);
            } else {
                for (var tc : batch) {
                    if (abort.get()) results.add(ToolResult.Builder.failure(tc.name(), "Aborted: sibling error", null));
                    else {
                        var r = execute(tc.name(), tc.arguments());
                        results.add(r);
                        if (r.isError() && isDestructive(tc.name())) abort.set(true);
                    }
                }
            }
        }
        return results;
    }

    // ── Permission check ──

    private ToolResult checkPermissions(String toolName, Map<String, Object> args) {
        if (decisionPipeline != null && permissionRules != null) {
            var use = new ToolUse(toolName, args);
            var decision = decisionPipeline.evaluate(use, permissionRules);
            if (decision instanceof Decision.HardDeny hd)
                return ToolResult.Builder.failure(toolName, "BLOCKED: " + hd.reason(), null);
            if (decision instanceof Decision.Deny d) {
                if (session != null && session.recordDenial(toolName))
                    log.warn("Tool '{}' blocked: max denials reached", toolName);
                return ToolResult.Builder.failure(toolName, "Permission denied: " + d.reason(), null);
            }
            if (decision instanceof Decision.Ask a && approvalBroker != null) {
                var approval = approvalBroker.request(use, sessionId);
                try {
                    decision = approval.get(30, TimeUnit.SECONDS);
                    if (decision instanceof Decision.Deny d)
                        return ToolResult.Builder.failure(toolName, "User denied: " + d.reason(), null);
                } catch (Exception e) {
                    return ToolResult.Builder.failure(toolName, "Approval failed: " + e.getMessage(), null);
                }
            }
        } else if (permissionRules != null) {
            if (permissionRules.resolve(toolName) == PermissionRules.Behavior.DENY) {
                return ToolResult.Builder.failure(toolName, "Permission denied: " + toolName, null);
            }
        }
        return null;
    }

    // ── Batch execution ──

    private List<List<ToolCall>> partition(List<ToolCall> calls) {
        var batches = new ArrayList<List<ToolCall>>();
        var current = new ArrayList<ToolCall>();
        boolean concurrent = true;
        for (var tc : calls) {
            boolean safe = isConcurrencySafe(tc.name());
            if (current.isEmpty()) { current.add(tc); concurrent = safe; }
            else if (concurrent && safe) current.add(tc);
            else { batches.add(List.copyOf(current)); current = new ArrayList<>(); current.add(tc); concurrent = safe; }
        }
        if (!current.isEmpty()) batches.add(List.copyOf(current));
        return batches;
    }

    private void executeParallel(List<ToolCall> batch, List<ToolResult> results, AtomicBoolean abort) {
        var futures = new ArrayList<CompletableFuture<ToolResult>>();
        for (var tc : batch) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (abort.get()) return ToolResult.Builder.failure(tc.name(), "Aborted", null);
                var r = execute(tc.name(), tc.arguments());
                if (r.isError() && isDestructive(tc.name())) abort.set(true);
                return r;
            }));
        }
        try { CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(timeoutMs * batch.size(), TimeUnit.MILLISECONDS); }
        catch (Exception e) { abort.set(true); }
        for (var f : futures) {
            try { results.add(f.getNow(ToolResult.Builder.failure("unknown", "No result", null))); }
            catch (Exception e) { results.add(ToolResult.Builder.failure("unknown", e.getMessage(), null)); }
        }
    }

    private boolean isConcurrencySafe(String name) { var t = registry.get(name); return t != null && t.isConcurrencySafe(); }
    private boolean isDestructive(String name) { var t = registry.get(name); return t != null && t.isDestructive(); }

    // ── Output normalization ──

    private Object normalize(Object result, String toolName) {
        if (result == null) return "[Tool " + toolName + " completed]";
        String text = result instanceof String s ? s : result.toString();
        if (text.length() > maxOutputChars) {
            String stored = ToolResultStorage.getInstance().store(toolName, text);
            if (stored != null) return stored;
            return text.substring(0, maxOutputChars / 2) + "\n... [" + (text.length() - maxOutputChars) + " chars truncated] ...\n" + text.substring(text.length() - maxOutputChars / 2);
        }
        return result;
    }

    private void fireToolEvent(String toolName, Map<String, Object> args, Object result, long elapsed) {
        if (events == null) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> evtArgs = Map.of("toolName", toolName, "elapsedMs", elapsed);
        events.post(new AgentEvents.ToolCalled(toolName, "agent", evtArgs, result, elapsed));
    }

    private static String summarizeArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "{}";
        var sb = new StringBuilder("{");
        for (var e : args.entrySet()) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(e.getKey()).append("=");
            Object v = e.getValue();
            sb.append(v instanceof String s && s.length() > 60 ? s.substring(0, 60) + "..." : v);
        }
        return sb.append("}").toString();
    }
}
