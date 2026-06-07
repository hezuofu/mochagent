// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Streaming execution strategy — executes tool calls as they arrive from
 * the API stream, using concurrency-safe partitioning (Claude Code pattern).
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #addTool} — called as tool_use blocks stream in</li>
 *   <li>Tools execute immediately if concurrency-safe, or queue behind unsafe siblings</li>
 *   <li>{@link #getResults} — drain all remaining, ordered by submission</li>
 * </ol>
 *
 * <p>Delegates actual execution to a {@link SequentialStrategy} so each
 * individual tool call benefits from retry + timeout.
 *
 * <p>This is the "stream-as-they-arrive" mode plugged into
 * {@link ToolExecutor} as the delegate strategy. For sync batch execution,
 * use {@link BatchStrategy} instead.
 *
 * @author lanxia39@163.com
 */
public final class StreamingStrategy implements ToolExecutionStrategy {

    private final SequentialStrategy executor;
    private final ToolRegistry registry;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tool-stream");
        t.setDaemon(true);
        return t;
    });

    private final List<TrackedCall> calls = new ArrayList<>();
    private final AtomicBoolean siblingAborted = new AtomicBoolean(false);
    private final List<Consumer<ToolEvent>> listeners = new ArrayList<>();

    public StreamingStrategy(ToolRegistry registry, long timeoutMs, int maxRetries, long retryDelayMs) {
        this.registry = registry;
        this.executor = new SequentialStrategy(registry, timeoutMs, maxRetries, retryDelayMs);
    }

    // ── ToolExecutionStrategy ──

    @Override
    public ToolResult execute(String name, Map<String, Object> args) {
        addTool(name, args);
        return getResults().stream()
                .filter(e -> e.name().equals(name))
                .findFirst()
                .map(e -> e.isError()
                        ? ToolResult.Builder.failure(name, e.error(), null)
                        : ToolResult.Builder.success(name, e.result(), e.durationMs()))
                .orElse(ToolResult.Builder.failure(name, "No result", null));
    }

    @Override
    public List<ToolResult> executeBatch(List<ToolCall> callsToExecute) {
        callsToExecute.forEach(c -> addTool(c.name(), c.arguments()));
        return getResults().stream()
                .map(e -> e.isError()
                        ? ToolResult.Builder.failure(e.name(), e.error(), null)
                        : ToolResult.Builder.success(e.name(), e.result(), e.durationMs()))
                .toList();
    }

    // ── Streaming API ──

    /** Add a tool call as it arrives from the stream. Executes immediately if safe. */
    public StreamingStrategy addTool(String name, Map<String, Object> args) {
        Tool tool = registry.get(name);
        if (tool == null) {
            calls.add(TrackedCall.notFound(name, args));
            return this;
        }

        boolean safe = tool.isConcurrencySafe() && !tool.isDestructive();
        TrackedCall tc = new TrackedCall(name, args, safe);
        calls.add(tc);

        if (canStart(tc)) {
            start(tc);
        }
        return this;
    }

    /** Non-blocking: yield completed results so far, keeping in-progress calls. */
    public List<ToolEvent> poll() {
        List<ToolEvent> completed = new ArrayList<>();
        for (TrackedCall tc : calls) {
            if (tc.consumed) {
                continue;
            }
            if (tc.status == Status.COMPLETED || tc.status == Status.ERROR) {
                completed.add(tc.toEvent());
                tc.consumed = true;
            }
        }
        calls.removeIf(c -> c.consumed);
        return completed;
    }

    /** Blocking: wait for all queued and running calls to finish, in submission order. */
    public List<ToolEvent> getResults() {
        // Start any queued calls that can now run
        for (TrackedCall tc : calls) {
            if (tc.status == Status.QUEUED) {
                // Wait for exclusive lock if needed
                waitForSlot(tc);
                if (tc.status == Status.QUEUED) {
                    start(tc);
                }
            }
        }

        // Drain remaining futures
        for (TrackedCall tc : calls) {
            if (tc.status == Status.RUNNING && tc.future != null) {
                try { tc.future.get(60, TimeUnit.SECONDS); }
                catch (Exception ignored) {}
            }
        }

        List<ToolEvent> results = new ArrayList<>();
        for (TrackedCall tc : calls) {
            if (tc.status == Status.QUEUED) {
                startSync(tc);
            }
            if (tc.status == Status.RUNNING && tc.future != null) {
                try { tc.future.get(60, TimeUnit.SECONDS); }
                catch (Exception ignored) {}
            }
            results.add(tc.toEvent());
        }
        calls.clear();
        return results;
    }

    /** Register a listener for per-tool completion events. */
    public StreamingStrategy onComplete(Consumer<ToolEvent> listener) {
        listeners.add(listener);
        return this;
    }

    /** Discard all pending tools — used when streaming falls back. */
    public void discard() {
        for (TrackedCall tc : calls) {
            if (tc.future != null && !tc.future.isDone()) {
                tc.future.cancel(true);
            }
        }
        calls.clear();
        siblingAborted.set(false);
    }

    public void shutdown() { pool.shutdown(); }

    // ── Internal ──

    private boolean canStart(TrackedCall tc) {
        if (tc.status != Status.QUEUED) {
            return false;
        }
        // Concurrency-safe: always can start
        if (tc.isConcurrencySafe) {
            return true;
        }
        // Non-safe: must be the ONLY running call
        return calls.stream().noneMatch(
                c -> c != tc && c.status == Status.RUNNING && !c.isConcurrencySafe);
    }

    private void waitForSlot(TrackedCall tc) {
        if (tc.isConcurrencySafe) {
            return;
        }
        TrackedCall blocking = null;
        while ((blocking = findBlocking(tc)) != null) {
            if (blocking.future != null) {
                try { blocking.future.get(60, TimeUnit.SECONDS); }
                catch (Exception ignored) {}
            }
        }
    }

    private TrackedCall findBlocking(TrackedCall tc) {
        return calls.stream()
                .filter(c -> c != tc && c.status == Status.RUNNING && !c.isConcurrencySafe)
                .findFirst().orElse(null);
    }

    private void start(TrackedCall tc) {
        tc.status = Status.RUNNING;
        tc.future = CompletableFuture.supplyAsync(() -> executeCall(tc), pool);
    }

    private void startSync(TrackedCall tc) {
        tc.status = Status.RUNNING;
        executeCall(tc);
    }

    private Void executeCall(TrackedCall tc) {
        long t0 = System.currentTimeMillis();
        try {
            ToolResult tr = executor.execute(tc.name, tc.args);
            tc.result = tr.isError() ? tr.error() : tr.output();
            tc.status = tr.isError() ? Status.ERROR : Status.COMPLETED;
            tc.error = tr.isError() ? tr.error() : null;
            tc.durationMs = System.currentTimeMillis() - t0;

            // Sibling abort: destructive tool error → cancel concurrent siblings
            Tool tool = registry.get(tc.name);
            if (tr.isError() && tool != null && (tool.isDestructive() || !tool.isConcurrencySafe())) {
                siblingAborted.set(true);
                for (TrackedCall sib : calls) {
                    if (sib != tc && sib.future != null && !sib.future.isDone()) {
                        sib.future.cancel(true);
                    }
                }
            }
        } catch (Exception e) {
            tc.error = e.getMessage();
            tc.status = Status.ERROR;
            tc.durationMs = System.currentTimeMillis() - t0;
        }

        // Notify listeners
        ToolEvent event = tc.toEvent();
        for (Consumer<ToolEvent> l : listeners) {
            l.accept(event);
        }
        return null;
    }

    // ── Types ──

    private enum Status { QUEUED, RUNNING, COMPLETED, ERROR, NOT_FOUND }

    private static class TrackedCall {
        final String name;
        final Map<String, Object> args;
        final boolean isConcurrencySafe;
        Status status = Status.QUEUED;
        boolean consumed;
        Object result;
        String error;
        long durationMs;
        CompletableFuture<Void> future;

        TrackedCall(String name, Map<String, Object> args, boolean safe) {
            this.name = name; this.args = args; this.isConcurrencySafe = safe;
        }
        static TrackedCall notFound(String name, Map<String, Object> args) {
            TrackedCall tc = new TrackedCall(name, args, true);
            tc.status = Status.NOT_FOUND;
            tc.error = "Tool not found: " + name;
            return tc;
        }
        ToolEvent toEvent() {
            return new ToolEvent(name, result, error, durationMs);
        }
    }

    public record ToolEvent(String name, Object result, String error, long durationMs) {
        public boolean isError() { return error != null; }
    }
}
