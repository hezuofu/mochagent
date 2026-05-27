// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;

/**
 * Sequential tool execution with retry — single tool, timeout, backoff.
 *
 * @author lanxia39@163.com
 */
class SequentialStrategy implements ToolExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(SequentialStrategy.class);

    private final ToolRegistry registry;
    private final long timeoutMs;
    private final int maxRetries;
    private final long retryDelayMs;

    SequentialStrategy(ToolRegistry registry, long timeoutMs, int maxRetries, long retryDelayMs) {
        this.registry = registry;
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
    }

    @Override
    public ToolResult execute(String toolName, Map<String, Object> arguments) {
        Tool tool = registry.get(toolName);
        if (tool == null) {
            return ToolResult.Builder.failure(toolName, "Tool not found: " + toolName, null);
        }

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            try {
                long start = System.currentTimeMillis();
                var future = CompletableFuture.supplyAsync(() -> tool.call(arguments));
                Object result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                long elapsed = System.currentTimeMillis() - start;
                log.info("[Tool] {} ({}ms)", toolName, elapsed);
                return ToolResult.Builder.success(toolName, result, elapsed);

            } catch (TimeoutException e) {
                lastError = io.sketch.mochaagents.MochaException.ToolException.timeout(toolName);
                log.warn("Tool '{}' timeout (attempt {}/{})", toolName, attempt, maxRetries + 1);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IllegalArgumentException iae) throw iae;
                lastError = io.sketch.mochaagents.MochaException.ToolException.execution(toolName, e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.Builder.failure(toolName, "Interrupted", null);
            } catch (Exception e) {
                lastError = io.sketch.mochaagents.MochaException.ToolException.execution(toolName, e.getMessage(), e);
            }

            if (attempt <= maxRetries) {
                try { Thread.sleep(retryDelayMs * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        return ToolResult.Builder.failure(toolName, lastError.getMessage(), null);
    }
}
