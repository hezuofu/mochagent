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

/**
 * Tool executor — Chain of Responsibility interceptors delegating to a
 * {@link ToolExecutionStrategy} (sequential by default, batch for multi-tool).
 *
 * <pre>{@code
 * var executor = new ToolExecutor(registry)
 *     .withHooks(hooks)
 *     .withPermissions(rules, pipeline, broker)
 *     .withEvents(eventBus)
 *     .withMaxOutputChars(200_000);
 *
 * // Single tool → sequential
 * executor.execute("read", Map.of("file", "a.txt"));
 *
 * // Multiple tools → batch with concurrency-safe partitioning
 * executor.executeBatch(List.of(
 *     new ToolCall("read", Map.of("file", "a.txt")),
 *     new ToolCall("grep", Map.of("pattern", "foo"))));
 * }</pre>
 *
 * @author lanxia39@163.com
 */
public class ToolExecutor implements ToolExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry registry;
    private final ToolExecutionStrategy strategy;
    private final BatchStrategy batchStrategy;

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
        this.strategy = new SequentialStrategy(registry, timeoutMs, maxRetries, retryDelayMs);
        this.batchStrategy = new BatchStrategy(registry, strategy);
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

    // ── ToolExecutionStrategy (interceptors → delegate) ──

    @Override
    public ToolResult execute(String toolName, Map<String, Object> arguments) {
        Tool tool = registry.get(toolName);
        if (tool == null) return ToolResult.Builder.failure(toolName, "Tool not found: " + toolName, null);

        // Interceptor 1: Validation
        ToolResult r = validate(tool, toolName, arguments);
        if (r != null) return r;

        // Interceptor 2: Permission
        r = checkPermissions(toolName, arguments);
        if (r != null) return r;

        // Interceptor 3: Pre-hooks + argument modification
        if (hooks != null) {
            var d = hooks.applyPreTool(tool, arguments);
            if (d.outcome() == Hooks.HookDecision.Outcome.DENY)
                return ToolResult.Builder.failure(toolName, "Hook denied: " + d.reason(), null);
            if (d.modifiedArgs() != null) arguments = d.modifiedArgs();
        }

        // Delegate to execution strategy
        var result = strategy.execute(toolName, arguments);

        // Interceptor 4: Post-hooks
        if (hooks != null) hooks.applyPostTool(tool, arguments, result.output(), msg -> {});

        // Interceptor 5: Normalization + event
        Object normalized = normalize(result.output(), toolName);
        fireEvent(toolName, arguments, normalized, result.durationMs());

        return ToolResult.Builder.success(toolName, normalized, result.durationMs());
    }

    @Override
    public List<ToolResult> executeBatch(List<ToolCall> calls) {
        // Run interceptors (validation + permission + pre-hooks) for each call, then batch-execute remainder
        var preResults = new ArrayList<ToolResult>();
        var remaining = new ArrayList<ToolCall>();

        for (var tc : calls) {
            Tool tool = registry.get(tc.name());
            if (tool == null) { preResults.add(ToolResult.Builder.failure(tc.name(), "Tool not found", null)); continue; }
            ToolResult r = validate(tool, tc.name(), tc.arguments());
            if (r != null) { preResults.add(r); continue; }
            r = checkPermissions(tc.name(), tc.arguments());
            if (r != null) { preResults.add(r); continue; }
            remaining.add(tc);
        }

        var results = batchStrategy.executeBatch(remaining);

        // Post-hooks + normalization
        for (var r : results) {
            if (hooks != null) hooks.applyPostTool(registry.get(r.toolName()), Map.of(), r.output(), msg -> {});
        }

        preResults.addAll(results);
        return preResults;
    }

    // ── Interceptor implementations ──

    private ToolResult validate(Tool tool, String name, Map<String, Object> args) {
        var v = tool.validateInput(args);
        if (!v.isValid()) return ToolResult.Builder.failure(name, "Validation failed: " + v.getMessage(), null);
        return null;
    }

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

    private Object normalize(Object result, String toolName) {
        if (result == null) return "[Tool " + toolName + " completed]";
        String text = result instanceof String s ? s : result.toString();
        if (text.length() > maxOutputChars) {
            String stored = ToolResultStorage.getInstance().store(toolName, text);
            if (stored != null) return stored;
            return text.substring(0, maxOutputChars / 2) + "\n... ["
                    + (text.length() - maxOutputChars) + " chars truncated] ...\n"
                    + text.substring(text.length() - maxOutputChars / 2);
        }
        return result;
    }

    private void fireEvent(String name, Map<String, Object> args, Object result, long elapsed) {
        if (events == null) return;
        events.post(new AgentEvents.ToolCalled(name, "agent", Map.of("toolName", name), result, elapsed));
    }
}
