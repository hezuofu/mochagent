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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Tool executor — Chain of Responsibility + Strategy.
 *
 * <p>Single entry point for ALL tool execution. The interceptor chain
 * (validate → permission → pre-hooks) runs regardless of execution mode.
 * The delegate {@link ToolExecutionStrategy} determines the execution model:
 *
 * <ul>
 *   <li>{@link SequentialStrategy} — sync one-at-a-time (default)</li>
 *   <li>{@link StreamingStrategy} — stream-as-they-arrive (Claude Code pattern)</li>
 * </ul>
 *
 * <p>Batch execution is handled by {@link BatchStrategy} which partitions
 * calls into concurrency-safe groups.
 *
 * <pre>{@code
 * // Sync mode (default)
 * var sync = new ToolExecutor(registry);
 * sync.execute("read", Map.of("file", "a.txt"));
 *
 * // Streaming mode
 * var stream = ToolExecutor.streaming(registry);
 * stream.addTool("read", Map.of("file", "a.txt"));  // runs interceptors + starts
 * stream.addTool("grep", Map.of("pattern", "foo")); // can run concurrently if safe
 * List<ToolEvent> results = stream.getResults();
 * }</pre>
 *
 * @author lanxia39@163.com
 */
public class ToolExecutor implements ToolExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry registry;
    private final ToolExecutionStrategy strategy;
    private final BatchStrategy batchStrategy;
    // null in sync mode
    private final StreamingStrategy streamingStrategy;

    private Hooks hooks;
    private PermissionRules permissionRules;
    private DecisionPipeline decisionPipeline;
    private ApprovalBroker approvalBroker;
    private Session session;
    private EventBus events;
    private String sessionId = "default";
    private int maxOutputChars = 500_000;

    // ── Constructors ──

    /** Sync executor (default). */
    public ToolExecutor(ToolRegistry registry) {
        this(registry, 60_000, 2, 500);
    }

    public ToolExecutor(ToolRegistry registry, long timeoutMs, int maxRetries, long retryDelayMs) {
        this.registry = registry;
        this.strategy = new SequentialStrategy(registry, timeoutMs, maxRetries, retryDelayMs);
        this.batchStrategy = new BatchStrategy(registry, strategy);
        this.streamingStrategy = null;
    }

    /** Streaming executor — tools execute as they arrive from the API stream. */
    private ToolExecutor(ToolRegistry registry, StreamingStrategy streaming) {
        this.registry = registry;
        this.strategy = streaming;
        this.batchStrategy = new BatchStrategy(registry, strategy);
        this.streamingStrategy = streaming;
    }

    /** Factory: create a streaming-capable executor. */
    public static ToolExecutor streaming(ToolRegistry registry) {
        return streaming(registry, 60_000, 2, 500);
    }

    public static ToolExecutor streaming(ToolRegistry registry, long timeoutMs, int maxRetries, long retryDelayMs) {
        return new ToolExecutor(registry, new StreamingStrategy(registry, timeoutMs, maxRetries, retryDelayMs));
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

    // ── Sync API (ToolExecutionStrategy) ──

    @Override
    public ToolResult execute(String toolName, Map<String, Object> arguments) {
        Tool tool = registry.get(toolName);
        if (tool == null) {
            return ToolResult.Builder.failure(toolName, "Tool not found: " + toolName, null);
        }

        ToolResult r = runPreInterceptors(tool, toolName, arguments);
        if (r != null) {
            return r;
        }

        return runPostInterceptors(strategy.execute(toolName, arguments), toolName, tool, arguments);
    }

    @Override
    public List<ToolResult> executeBatch(List<ToolCall> calls) {
        var preResults = new ArrayList<ToolResult>();
        var remaining = new ArrayList<ToolCall>();

        for (var tc : calls) {
            Tool tool = registry.get(tc.name());
            if (tool == null) { preResults.add(ToolResult.Builder.failure(tc.name(), "Tool not found", null)); continue; }
            ToolResult r = runPreInterceptors(tool, tc.name(), tc.arguments());
            if (r != null) { preResults.add(r); continue; }
            remaining.add(tc);
        }

        for (var r : batchStrategy.executeBatch(remaining)) {
            Tool tool = registry.get(r.toolName());
            preResults.add(runPostInterceptors(r, r.toolName(), tool, Map.of()));
        }
        return preResults;
    }

    // ── Streaming API ──

    public boolean isStreaming() { return streamingStrategy != null; }

    /** Add a tool call as it streams in. Runs pre-interceptors immediately, then enqueues for execution. */
    public ToolExecutor addTool(String toolName, Map<String, Object> arguments) {
        if (!isStreaming()) {
            throw new IllegalStateException("Not in streaming mode — use ToolExecutor.streaming() factory");
        }

        Tool tool = registry.get(toolName);
        if (tool == null) {
            // will record NOT_FOUND
            streamingStrategy.addTool(toolName, arguments);
            return this;
        }

        // Run pre-interceptors NOW (before enqueuing)
        ToolResult blocked = runPreInterceptors(tool, toolName, arguments);
        if (blocked != null) {
            // Enqueue a pre-failed result
            // placeholder
            streamingStrategy.addTool(toolName, arguments);
            return this;
        }

        // Delegate to streaming strategy
        streamingStrategy.addTool(toolName, arguments);
        return this;
    }

    /** Non-blocking: return completed results so far. */
    public List<StreamingStrategy.ToolEvent> pollResults() {
        if (!isStreaming()) {
            return List.of();
        }
        List<StreamingStrategy.ToolEvent> raw = streamingStrategy.poll();
        List<StreamingStrategy.ToolEvent> normalized = new ArrayList<>(raw.size());
        for (var e : raw) {
            if (!e.isError()) {
                Tool tool = registry.get(e.name());
                if (tool != null) {
                    Object out = normalize(e.result(), e.name());
                    fireEvent(e.name(), Map.of(), out, e.durationMs());
                }
            }
            normalized.add(e);
        }
        return normalized;
    }

    /** Blocking: wait for all queued/running calls. Results in submission order with post-interceptors applied. */
    public List<StreamingStrategy.ToolEvent> getResults() {
        if (!isStreaming()) {
            return List.of();
        }
        List<StreamingStrategy.ToolEvent> raw = streamingStrategy.getResults();
        List<StreamingStrategy.ToolEvent> normalized = new ArrayList<>(raw.size());
        for (var e : raw) {
            Tool tool = registry.get(e.name());
            if (tool != null && !e.isError()) {
                Object out = normalize(e.result(), e.name());
                fireEvent(e.name(), Map.of(), out, e.durationMs());
                normalized.add(new StreamingStrategy.ToolEvent(e.name(), out, null, e.durationMs()));
            } else {
                normalized.add(e);
            }
        }
        return normalized;
    }

    /** Register for per-tool completion events in streaming mode. */
    public ToolExecutor onToolComplete(Consumer<StreamingStrategy.ToolEvent> listener) {
        if (streamingStrategy != null) {
            streamingStrategy.onComplete(listener);
        }
        return this;
    }

    /** Discard all pending tools (streaming fallback). */
    public void discard() {
        if (streamingStrategy != null) {
            streamingStrategy.discard();
        }
    }

    public void shutdown() {
        if (streamingStrategy != null) {
            streamingStrategy.shutdown();
        }
    }

    // ── Interceptor chain ──

    /** Run pre-execution interceptors. Returns a failure result if the call is blocked. */
    private ToolResult runPreInterceptors(Tool tool, String toolName, Map<String, Object> arguments) {
        // 1. Validate
        var v = tool.validateInput(arguments);
        if (!v.isValid()) {
            return ToolResult.Builder.failure(toolName, "Validation failed: " + v.getMessage(), null);
        }

        // 2. Permission
        ToolResult r = checkPermissions(toolName, arguments);
        if (r != null) {
            return r;
        }

        // 3. Pre-hooks (may modify args)
        if (hooks != null) {
            var d = hooks.applyPreTool(tool, arguments);
            if (d.outcome() == Hooks.HookDecision.Outcome.DENY) {
                return ToolResult.Builder.failure(toolName, "Hook denied: " + d.reason(), null);
            }
        }
        return null;
    }

    /** Run post-execution interceptors: hooks + normalize + event. */
    private ToolResult runPostInterceptors(ToolResult result, String toolName, Tool tool, Map<String, Object> arguments) {
        if (hooks != null) {
            hooks.applyPostTool(tool, arguments, result.output(), msg -> {});
        }
        Object normalized = normalize(result.output(), toolName);
        fireEvent(toolName, arguments, normalized, result.durationMs());
        if (result.isError()) {
            return result;
        }
        return ToolResult.Builder.success(toolName, normalized, result.durationMs());
    }

    // ── Interceptor implementations ──

    private ToolResult checkPermissions(String toolName, Map<String, Object> args) {
        if (decisionPipeline != null && permissionRules != null) {
            var use = new ToolUse(toolName, args);
            var decision = decisionPipeline.evaluate(use, permissionRules);
            if (decision instanceof Decision.HardDeny hd) {
                return ToolResult.Builder.failure(toolName, "BLOCKED: " + hd.reason(), null);
            }
            if (decision instanceof Decision.Deny d) {
                if (session != null && session.recordDenial(toolName)) {
                    log.warn("Tool '{}' blocked: max denials reached", toolName);
                }
                return ToolResult.Builder.failure(toolName, "Permission denied: " + d.reason(), null);
            }
            if (decision instanceof Decision.Ask a && approvalBroker != null) {
                var approval = approvalBroker.request(use, sessionId);
                try {
                    decision = approval.get(30, TimeUnit.SECONDS);
                    if (decision instanceof Decision.Deny d) {
                        return ToolResult.Builder.failure(toolName, "User denied: " + d.reason(), null);
                    }
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

    private Object normalize(Object result, String toolName) {
        if (result == null) {
            return "[Tool " + toolName + " completed]";
        }
        String text = result instanceof String s ? s : result.toString();
        if (text.length() > maxOutputChars) {
            String stored = ToolResultStorage.getInstance().store(toolName, text);
            if (stored != null) {
                return stored;
            }
            return text.substring(0, maxOutputChars / 2) + "\n... ["
                    + (text.length() - maxOutputChars) + " chars truncated] ...\n"
                    + text.substring(text.length() - maxOutputChars / 2);
        }
        return result;
    }

    private void fireEvent(String name, Map<String, Object> args, Object result, long elapsed) {
        if (events == null) {
            return;
        }
        events.post(new AgentEvents.ToolCalled(name, "agent", Map.of("toolName", name), result, elapsed));
    }
}
