// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;

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
    private io.sketch.mochaagents.tool.Hooks hooks;
    private io.sketch.mochaagents.interaction.PermissionRules permissions;
    private io.sketch.mochaagents.interaction.DecisionPipeline pipeline;
    private io.sketch.mochaagents.interaction.ApprovalBroker broker;
    private io.sketch.mochaagents.interaction.Session session;
    private io.sketch.mochaagents.agent.event.AgentEvents events;

    public ToolExecutor(ToolRegistry registry, long timeoutMs, int maxRetries, long retryDelayMs) {
        this.registry = registry;
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
    }

    public ToolExecutor(ToolRegistry registry) { this(registry, 60_000, 2, 500); }

    /** Inject hooks for pre/post tool interception. */
    public ToolExecutor withHooks(io.sketch.mochaagents.tool.Hooks hooks) { this.hooks = hooks; return this; }
    /** Inject permission rules for tool gating. */
    public ToolExecutor withPermissions(io.sketch.mochaagents.interaction.PermissionRules permissions) { this.permissions = permissions; return this; }
    /** Inject decision pipeline (rules + safety + mode). */
    public ToolExecutor withPipeline(io.sketch.mochaagents.interaction.DecisionPipeline pipeline) { this.pipeline = pipeline; return this; }
    /** Inject approval broker for ASK decisions. */
    public ToolExecutor withBroker(io.sketch.mochaagents.interaction.ApprovalBroker broker) { this.broker = broker; return this; }
    /** Inject event bus for real-time tool call notifications (diff display etc.). */
    public ToolExecutor withEvents(io.sketch.mochaagents.agent.event.AgentEvents events) { this.events = events; return this; }
    /** Inject session for denial tracking. */
    public ToolExecutor withSession(io.sketch.mochaagents.interaction.Session session) { this.session = session; return this; }

    public ToolResult execute(String toolName, Map<String, Object> arguments) {
        Tool tool = registry.get(toolName);
        if (tool == null) {
            log.warn("Tool '{}' not found", toolName);
            return ToolResult.Builder.failure(toolName, "Tool not found: " + toolName, null);
        }

        // Permission check — pipeline or simple rules
        if (pipeline != null && permissions != null) {
            var use = new io.sketch.mochaagents.interaction.ToolUse(toolName, arguments);
            var decision = pipeline.evaluate(use, permissions);
            if (decision instanceof io.sketch.mochaagents.interaction.Decision.HardDeny hd) {
                return ToolResult.Builder.failure(toolName, "BLOCKED: " + hd.reason(), null);
            }
            if (decision instanceof io.sketch.mochaagents.interaction.Decision.Deny d) {
                if (session != null && session.recordDenial(toolName))
                    log.warn("Tool '{}' auto-blocked after {} denials", toolName, session.maxDenialsBeforeBlock());
                return ToolResult.Builder.failure(toolName, "Permission denied: " + d.reason(), null);
            }
            if (decision instanceof io.sketch.mochaagents.interaction.Decision.Ask a && broker != null) {
                String sessionId = "default"; // TODO: wire from AgentContext
                var approval = broker.request(use, sessionId);
                try {
                    decision = approval.get(30, java.util.concurrent.TimeUnit.SECONDS);
                    if (decision instanceof io.sketch.mochaagents.interaction.Decision.Deny d)
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
                // Post-tool hooks
                if (hooks != null) hooks.applyPostTool(tool, arguments, result, msg -> {});
                // Fire tool call event for real-time display (diff etc.)
                if (events != null) {
                    Map<String, Object> eventData = buildToolEventData(toolName, arguments, result, elapsed);
                    events.fire(new io.sketch.mochaagents.agent.event.AgentEvents.Event(
                            io.sketch.mochaagents.agent.event.AgentEvents.TOOL_CALL,
                            toolName, eventData, elapsed));
                }
                return ToolResult.Builder.success(toolName, result, elapsed);

            } catch (TimeoutException e) {
                lastError = new RuntimeException("Tool '" + toolName + "' timed out after " + timeoutMs + "ms");
                log.warn("Tool '{}' timeout (attempt {}/{})", toolName, attempt, maxRetries + 1);
            } catch (ExecutionException e) {
                // Non-retryable: validation/permission errors should fail fast
                if (e.getCause() instanceof IllegalArgumentException) throw (IllegalArgumentException) e.getCause();
                lastError = new RuntimeException("Tool '" + toolName + "' failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = new RuntimeException("Tool '" + toolName + "' interrupted", e);
            } catch (Exception e) {
                lastError = new RuntimeException("Tool '" + toolName + "' failed: " + e.getMessage(), e);
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
