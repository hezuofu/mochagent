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
    private io.sketch.mochaagents.agent.react.Hooks hooks;
    private io.sketch.mochaagents.interaction.permission.PermissionRules permissions;
    private io.sketch.mochaagents.agent.AgentEvents events;

    public ToolExecutor(ToolRegistry registry, long timeoutMs, int maxRetries, long retryDelayMs) {
        this.registry = registry;
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
    }

    public ToolExecutor(ToolRegistry registry) { this(registry, 60_000, 2, 500); }

    /** Inject hooks for pre/post tool interception. */
    public ToolExecutor withHooks(io.sketch.mochaagents.agent.react.Hooks hooks) { this.hooks = hooks; return this; }
    /** Inject permission rules for tool gating. */
    public ToolExecutor withPermissions(io.sketch.mochaagents.interaction.permission.PermissionRules permissions) { this.permissions = permissions; return this; }
    /** Inject event bus for real-time tool call notifications (diff display etc.). */
    public ToolExecutor withEvents(io.sketch.mochaagents.agent.AgentEvents events) { this.events = events; return this; }

    public ToolResult execute(String toolName, Map<String, Object> arguments) {
        Tool tool = registry.get(toolName);
        if (tool == null) {
            log.warn("Tool '{}' not found", toolName);
            return ToolResult.Builder.failure(toolName, "Tool not found: " + toolName, null);
        }

        // Permission check
        if (permissions != null) {
            var perm = permissions.resolve(toolName);
            if (perm == io.sketch.mochaagents.interaction.permission.PermissionRules.Behavior.DENY) {
                return ToolResult.Builder.failure(toolName, "Permission denied: " + toolName, null);
            }
        }

        // Pre-tool hooks
        if (hooks != null) {
            var decision = hooks.applyPreTool(tool, arguments);
            if (decision.outcome() == io.sketch.mochaagents.agent.react.Hooks.HookDecision.Outcome.DENY)
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
                    events.fire(new io.sketch.mochaagents.agent.AgentEvents.Event(
                            io.sketch.mochaagents.agent.AgentEvents.TOOL_CALL,
                            toolName, eventData, elapsed));
                }
                return ToolResult.Builder.success(toolName, result, elapsed);

            } catch (TimeoutException e) {
                lastError = new RuntimeException("Tool '" + toolName + "' timed out after " + timeoutMs + "ms");
                log.warn("Tool '{}' timeout (attempt {}/{})", toolName, attempt, maxRetries + 1);
            } catch (ExecutionException | InterruptedException e) {
                lastError = new RuntimeException("Tool '" + toolName + "' failed: " + e.getMessage(), e);
                log.warn("Tool '{}' failed (attempt {}/{}): {}", toolName, attempt, maxRetries + 1, e.getMessage());
            } catch (Exception e) {
                lastError = new RuntimeException(e);
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
