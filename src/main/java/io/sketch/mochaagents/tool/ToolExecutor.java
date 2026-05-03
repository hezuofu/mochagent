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

    public ToolExecutor(ToolRegistry registry, long timeoutMs, int maxRetries, long retryDelayMs) {
        this.registry = registry;
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
    }

    public ToolExecutor(ToolRegistry registry) { this(registry, 60_000, 2, 500); }

    public ToolResult execute(String toolName, Map<String, Object> arguments) {
        Tool tool = registry.get(toolName);
        if (tool == null) {
            log.warn("Tool '{}' not found", toolName);
            return ToolResult.Builder.failure(toolName, "Tool not found: " + toolName, null);
        }

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            try {
                long start = System.currentTimeMillis();
                CompletableFuture<Object> future = CompletableFuture.supplyAsync(
                        () -> tool.call(arguments));
                Object result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                long elapsed = System.currentTimeMillis() - start;

                log.info("[Tool] {} ({}ms) args={}", toolName, elapsed, summarizeArgs(arguments));
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
