package io.sketch.mochaagents.tool;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Streaming tool executor — executes tools as they arrive from the API stream,
 * with concurrency control (safe tools parallel, unsafe tools serial).
 * Pattern from claude-code's StreamingToolExecutor (services/tools/StreamingToolExecutor.ts).
 * @author lanxia39@163.com
 */
public final class StreamingToolExecutor {

    private final ToolRegistry registry;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<TrackedTool> tools = new ArrayList<>();
    private final List<Consumer<ToolEvent>> listeners = new ArrayList<>();

    public StreamingToolExecutor(ToolRegistry registry) { this.registry = registry; }

    /** Add a tool call as it streams in. Starts execution immediately if safe. */
    public StreamingToolExecutor addTool(String name, Map<String, Object> args) {
        Tool tool = registry.get(name);
        if (tool == null) { tools.add(new TrackedTool(name, args, "not_found", true)); return this; }
        boolean safe = tool.isConcurrencySafe() && !tool.isDestructive();
        TrackedTool tt = new TrackedTool(name, args, "queued", false);
        tools.add(tt);

        if (canExecute(tt)) executeTool(tt, tool);
        return this;
    }

    /** Get results as they complete (non-blocking). */
    public List<ToolEvent> getCompletedResults() {
        List<ToolEvent> completed = new ArrayList<>();
        for (TrackedTool tt : tools) {
            if ("completed".equals(tt.status) || "error".equals(tt.status)) {
                completed.add(new ToolEvent(tt.name, tt.result, tt.error, tt.durationMs));
                tt.consumed = true;
            }
        }
        tools.removeIf(t -> t.consumed);
        return completed;
    }

    /** Wait for all remaining tools. */
    public List<ToolEvent> getRemainingResults() {
        for (TrackedTool tt : tools) {
            if ("queued".equals(tt.status)) {
                Tool tool = registry.get(tt.name);
                if (tool != null && canExecute(tt)) executeTool(tt, tool);
            }
        }
        // Wait for running tools
        for (TrackedTool tt : tools) {
            if (tt.future != null) {
                try { tt.future.get(60, TimeUnit.SECONDS); }
                catch (Exception ignored) {}
            }
        }
        List<ToolEvent> results = new ArrayList<>();
        for (TrackedTool tt : tools) {
            results.add(new ToolEvent(tt.name, tt.result, tt.error, tt.durationMs));
        }
        tools.clear();
        return results;
    }

    public void onEvent(Consumer<ToolEvent> listener) { listeners.add(listener); }

    private boolean canExecute(TrackedTool tt) {
        if (!"queued".equals(tt.status)) return false;
        Tool tool = registry.get(tt.name);
        if (tool == null) return true;
        if (!tool.isConcurrencySafe()) {
            for (TrackedTool other : tools) {
                if (other != tt && "running".equals(other.status)) {
                    Tool otherTool = registry.get(other.name);
                    if (otherTool != null && !otherTool.isConcurrencySafe()) return false;
                }
            }
        }
        return true;
    }

    private void executeTool(TrackedTool tt, Tool tool) {
        tt.status = "running";
        tt.future = CompletableFuture.supplyAsync(() -> {
            long t0 = System.currentTimeMillis();
            try {
                tt.result = tool.call(tt.args);
                tt.status = "completed";
                tt.durationMs = System.currentTimeMillis() - t0;
                listeners.forEach(l -> l.accept(new ToolEvent(tt.name, tt.result, null, tt.durationMs)));
            } catch (Exception e) {
                tt.error = e.getMessage();
                tt.status = "error";
                tt.durationMs = System.currentTimeMillis() - t0;
                // Sibling abort: if a bash-like tool errors, cancel concurrent siblings
                if (tool.isDestructive() || !tool.isConcurrencySafe()) {
                    for (TrackedTool sib : tools) {
                        if (sib != tt && sib.future != null && !sib.future.isDone())
                            sib.future.cancel(true);
                    }
                }
            }
            return null;
        }, executor);
    }

    private static class TrackedTool {
        final String name; final Map<String, Object> args;
        String status; boolean consumed; Object result; String error;
        long durationMs; CompletableFuture<Void> future;
        TrackedTool(String n, Map<String, Object> a, String s, boolean c) { name=n; args=a; status=s; consumed=c; }
    }

    public record ToolEvent(String name, Object result, String error, long durationMs) {
        public boolean isError() { return error != null; }
    }
}
