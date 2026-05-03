package io.sketch.mochaagents.tool;

import java.util.*;
import java.util.concurrent.*;

/**
 * Parallel tool executor — runs multiple tool calls concurrently (smolagents pattern).
 * @author lanxia39@163.com
 */
public final class ParallelToolExecutor {

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** Execute multiple tool calls in parallel. Returns results in call order. */
    public List<ToolResult> executeAll(ToolRegistry registry, List<ToolCall> calls) {
        if (calls.isEmpty()) return List.of();
        if (calls.size() == 1) {
            ToolCall c = calls.get(0);
            return List.of(new ToolExecutor(registry).execute(c.name, c.arguments));
        }

        // Parallel execution
        List<CompletableFuture<ToolResult>> futures = calls.stream()
                .map(c -> CompletableFuture.supplyAsync(
                        () -> new ToolExecutor(registry).execute(c.name, c.arguments), executor))
                .toList();

        return futures.stream().map(CompletableFuture::join).toList();
    }

    /** Execute in parallel and merge observations into a single string. */
    public String executeAndMerge(ToolRegistry registry, List<ToolCall> calls) {
        List<ToolResult> results = executeAll(registry, calls);
        StringBuilder sb = new StringBuilder();
        for (var r : results) {
            sb.append("[").append(r.toolName()).append("] ");
            if (r.isError()) sb.append("Error: ").append(r.error());
            else sb.append(r.output());
            sb.append("\n");
        }
        return sb.toString();
    }

    /** A tool call description. */
    public record ToolCall(String name, Map<String, Object> arguments) {}
}
