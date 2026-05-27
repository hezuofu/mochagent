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
 * Tool executor — timeout, retry, and result wrapping for resilient tool calls.
 * @author lanxia39@163.com
 */
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry registry;
    private final long timeoutMs;
    private final int maxRetries;
    private final long retryDelayMs;
    private Hooks hooks;
    private PermissionRules permissions;
    private DecisionPipeline pipeline;
    private ApprovalBroker broker;
    private Session session;
    private EventBus events;
    private String sessionId = "default";
    private int maxOutputChars = 500_000;

    public ToolExecutor(ToolRegistry registry, long timeoutMs, int maxRetries, long retryDelayMs) {
        this.registry = registry;
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
    }

    public ToolExecutor(ToolRegistry registry) { this(registry, 60_000, 2, 500); }

    /** Set max output characters before truncation (Claude Code truncateToolOutput pattern). */
    public ToolExecutor withMaxOutputChars(int n) { this.maxOutputChars = n; return this; }

    public ToolExecutor withHooks(Hooks hooks) { this.hooks = hooks; return this; }
    public ToolExecutor withPermissions(PermissionRules permissions) { this.permissions = permissions; return this; }
    public ToolExecutor withPipeline(DecisionPipeline pipeline) { this.pipeline = pipeline; return this; }
    public ToolExecutor withBroker(ApprovalBroker broker) { this.broker = broker; return this; }
    public ToolExecutor withEvents(EventBus events) { this.events = events; return this; }
    public ToolExecutor withSession(Session session) { this.session = session; return this; }
    /** Set current session ID (from AgentContext). */
    public ToolExecutor withSessionId(String id) { this.sessionId = id != null ? id : "default"; return this; }

    public ToolResult execute(String toolName, Map<String, Object> arguments) {
        Tool tool = registry.get(toolName);
        if (tool == null) {
            log.warn("Tool '{}' not found", toolName);
            return ToolResult.Builder.failure(toolName, "Tool not found: " + toolName, null);
        }

        // Schema validation — Pydantic/Zod pattern: validate Map args against tool schema
        ValidationResult validation = tool.validateInput(arguments);
        if (!validation.isValid()) {
            log.warn("Tool '{}' validation failed: {}", toolName, validation.getMessage());
            return ToolResult.Builder.failure(toolName, "Validation failed: " + validation.getMessage(), null);
        }

        // Permission check — pipeline or simple rules
        if (pipeline != null && permissions != null) {
            var use = new ToolUse(toolName, arguments);
            var decision = pipeline.evaluate(use, permissions);
            if (decision instanceof Decision.HardDeny hd) {
                return ToolResult.Builder.failure(toolName, "BLOCKED: " + hd.reason(), null);
            }
            if (decision instanceof Decision.Deny d) {
                if (session != null && session.recordDenial(toolName))
                    log.warn("Tool '{}' blocked: max denials reached", toolName);
                return ToolResult.Builder.failure(toolName, "Permission denied: " + d.reason(), null);
            }
            if (decision instanceof Decision.Ask a && broker != null) {
                var approval = broker.request(use, sessionId);
                try {
                    decision = approval.get(30, TimeUnit.SECONDS);
                    if (decision instanceof Decision.Deny d)
                        return ToolResult.Builder.failure(toolName, "User denied: " + d.reason(), null);
                } catch (Exception e) {
                    return ToolResult.Builder.failure(toolName, "Approval failed: " + e.getMessage(), null);
                }
            }
        } else if (permissions != null) {
            if (permissions.resolve(toolName) == io.sketch.mochaagents.interaction.PermissionRules.Behavior.DENY) {
                return ToolResult.Builder.failure(toolName, "Permission denied: " + toolName, null);
            }
        }

        // Pre-tool hooks
        if (hooks != null) {
            var decision = hooks.applyPreTool(tool, arguments);
            if (decision.outcome() == io.sketch.mochaagents.tool.Hooks.HookDecision.Outcome.DENY)
                return ToolResult.Builder.failure(toolName, "Hook denied: " + decision.reason(), null);
            if (decision.modifiedArgs() != null) arguments = decision.modifiedArgs();
        }

        final Map<String, Object> finalArgs = arguments;
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            try {
                long start = System.currentTimeMillis();
                CompletableFuture<Object> future = CompletableFuture.supplyAsync(
                        () -> tool.call(finalArgs));
                Object result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                long elapsed = System.currentTimeMillis() - start;

                log.info("[Tool] {} ({}ms) args={}", toolName, elapsed, summarizeArgs(arguments));
                result = normalize(result, toolName);
                // Post-tool hooks
                if (hooks != null) hooks.applyPostTool(tool, arguments, result, msg -> {});
                // Fire tool call event for real-time display (diff etc.)
                if (events != null) {
                    Map<String, Object> eventData = buildToolEventData(toolName, arguments, result, elapsed);
                    Object rawArgs = eventData.get("arguments");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = rawArgs instanceof Map<?,?> m
                            ? (Map<String, Object>) m : Map.of();
                    events.post(new AgentEvents.ToolCalled(
                            toolName, "agent", args, eventData.get("result"), elapsed));
                }
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
                log.warn("Tool '{}' error (attempt {}/{}): {}", toolName, attempt, maxRetries + 1, e.getMessage());
            }

            if (attempt <= maxRetries) {
                try { Thread.sleep(retryDelayMs * attempt); } // linear backoff
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        log.error("Tool '{}' exhausted {} retries", toolName, maxRetries + 1);
        return ToolResult.Builder.failure(toolName, lastError != null ? lastError.getMessage() : "exhausted retries", null);
    }

    // ── Batch execution (Claude Code partitionToolCalls pattern) ──

    /** A named tool call for batch execution. */
    public record ToolCall(String name, Map<String, Object> arguments) {}

    /**
     * Execute multiple tool calls with concurrent-safe batching.
     * Consecutive concurrent-safe tools run in parallel; unsafe tools serialize.
     * Destructive tool errors abort remaining siblings.
     */
    public List<ToolResult> executeBatch(List<ToolCall> calls) {
        if (calls.isEmpty()) return List.of();
        if (calls.size() == 1) {
            return List.of(execute(calls.get(0).name, calls.get(0).arguments));
        }

        List<List<ToolCall>> batches = partition(calls);
        List<ToolResult> results = new ArrayList<>();
        AtomicBoolean abort = new AtomicBoolean(false);

        for (var batch : batches) {
            if (abort.get()) {
                for (var tc : batch) {
                    results.add(ToolResult.Builder.failure(tc.name, "Aborted: sibling error", null));
                }
                continue;
            }
            if (batch.size() > 1 && isConcurrencySafe(batch.get(0).name)) {
                results.addAll(executeParallel(batch, abort));
            } else {
                for (var tc : batch) {
                    if (abort.get()) {
                        results.add(ToolResult.Builder.failure(tc.name, "Aborted: sibling error", null));
                    } else {
                        var r = execute(tc.name, tc.arguments);
                        results.add(r);
                        if (r.isError() && isDestructive(tc.name)) abort.set(true);
                    }
                }
            }
        }
        return results;
    }

    private List<List<ToolCall>> partition(List<ToolCall> calls) {
        List<List<ToolCall>> batches = new ArrayList<>();
        List<ToolCall> current = new ArrayList<>();
        boolean currentConcurrent = true;

        for (var tc : calls) {
            boolean safe = isConcurrencySafe(tc.name);
            if (current.isEmpty()) {
                current.add(tc);
                currentConcurrent = safe;
            } else if (currentConcurrent && safe) {
                current.add(tc);
            } else {
                batches.add(List.copyOf(current));
                current = new ArrayList<>();
                current.add(tc);
                currentConcurrent = safe;
            }
        }
        if (!current.isEmpty()) batches.add(List.copyOf(current));
        return batches;
    }

    private List<ToolResult> executeParallel(List<ToolCall> batch, AtomicBoolean abort) {
        List<ToolResult> results = new ArrayList<>();
        List<CompletableFuture<ToolResult>> futures = new ArrayList<>();
        for (var tc : batch) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (abort.get()) return ToolResult.Builder.failure(tc.name, "Aborted", null);
                var r = execute(tc.name, tc.arguments);
                if (r.isError() && isDestructive(tc.name)) {
                    abort.set(true);
                }
                return r;
            }));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(timeoutMs * batch.size(), TimeUnit.MILLISECONDS);
        } catch (Exception e) { abort.set(true); }
        for (var f : futures) {
            try { results.add(f.getNow(ToolResult.Builder.failure("unknown", "No result", null))); }
            catch (Exception e) { results.add(ToolResult.Builder.failure("unknown", e.getMessage(), null)); }
        }
        return results;
    }

    private boolean isConcurrencySafe(String toolName) {
        Tool t = registry.get(toolName);
        return t != null && t.isConcurrencySafe();
    }

    private boolean isDestructive(String toolName) {
        Tool t = registry.get(toolName);
        return t != null && t.isDestructive();
    }

    // ── Event helpers ──

    private static Map<String, Object> buildToolEventData(String toolName, Map<String, Object> args,
                                                             Object result, long elapsed) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("toolName", toolName);
        data.put("arguments", args);
        data.put("result", result != null ? result.toString() : "");
        data.put("elapsedMs", elapsed);

        // Extract diff info for file-modifying tools (write, edit, bash)
        if (("write".equals(toolName) || "edit".equals(toolName) || "bash".equals(toolName))
                && args.containsKey("file_path")) {
            data.put("file", args.get("file_path"));
            data.put("type", "modify");
            if (args.containsKey("old_content")) data.put("oldContent", args.get("old_content"));
            if (args.containsKey("content") || args.containsKey("new_content"))
                data.put("newContent", args.getOrDefault("content", args.get("new_content")));
            if (result != null) data.put("output", result.toString());
        }

        return data;
    }

    // ── Output normalization (Claude Code normalizeToolResult + truncateToolOutput) ──

    private Object normalize(Object result, String toolName) {
        if (result == null) return "[Tool " + toolName + " completed]";
        String text = result instanceof String s ? s : result.toString();
        if (text.length() > maxOutputChars) {
            String head = text.substring(0, maxOutputChars / 2);
            String tail = text.substring(text.length() - maxOutputChars / 2);
            return head + "\n... [" + (text.length() - maxOutputChars) + " chars truncated] ...\n" + tail;
        }
        return result;
    }

    private static String summarizeArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        args.forEach((k, v) -> {
            String val = String.valueOf(v);
            if (val.length() > 80) val = val.substring(0, 80) + "...";
            sb.append(k).append("=").append(val).append(", ");
        });
        sb.setLength(sb.length() - 2);
        return sb.append("}").toString();
    }
}
