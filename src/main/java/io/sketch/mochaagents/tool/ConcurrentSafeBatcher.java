package io.sketch.mochaagents.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Concurrent-safe tool batching — replicates claude-code's
 * {@code partitionToolCalls} + {@code runTools} pattern.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Group consecutive concurrent-safe tools into parallel batches.</li>
 *   <li>Each non-concurrent tool gets its own singleton batch (serial execution).</li>
 *   <li>Batches execute in order; within a batch, tools run in parallel (up to maxConcurrency).</li>
 *   <li>Sibling abort on error: destructive tool errors cancel all running siblings.</li>
 * </ol>
 *
 * <pre>{@code
 * var batcher = new ConcurrentSafeBatcher(registry, 10);
 * List<ToolResult> results = batcher.execute(List.of(
 *     new ToolCall("read", Map.of("file", "a.txt")),   // safe
 *     new ToolCall("grep", Map.of("pattern", "foo")),  // safe → same batch
 *     new ToolCall("edit", Map.of("file", "b.txt")),   // unsafe → new batch
 *     new ToolCall("read", Map.of("file", "c.txt"))    // safe → new batch
 * ));
 * }</pre>
 *
 * @author lanxia39@163.com
 */
public class ConcurrentSafeBatcher {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentSafeBatcher.class);

    private final ToolRegistry registry;
    private final int maxConcurrency;
    private final long timeoutMs;
    private final ToolExecutor baseExecutor;

    public ConcurrentSafeBatcher(ToolRegistry registry) {
        this(registry, 10, 60_000);
    }

    public ConcurrentSafeBatcher(ToolRegistry registry, int maxConcurrency, long timeoutMs) {
        this.registry = registry;
        this.maxConcurrency = maxConcurrency;
        this.timeoutMs = timeoutMs;
        this.baseExecutor = new ToolExecutor(registry, timeoutMs, 2, 500);
    }

    /**
     * Execute a list of tool calls with concurrent-safe batching.
     * Results are returned in the same order as the input calls.
     */
    public List<ToolResult> execute(List<ToolCall> calls) {
        if (calls.isEmpty()) return List.of();
        if (calls.size() == 1) {
            return List.of(baseExecutor.execute(calls.get(0).name, calls.get(0).arguments));
        }

        // 1. Partition into batches
        List<Batch> batches = partition(calls);

        // 2. Execute batches sequentially; within each batch, tools run in parallel
        Map<Integer, ToolResult> resultsByIndex = new LinkedHashMap<>();
        AtomicBoolean abortSiblings = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(maxConcurrency);

        try {
            for (Batch batch : batches) {
                if (abortSiblings.get()) {
                    // Fill remaining results with abort errors
                    for (ToolCall tc : batch.calls) {
                        resultsByIndex.put(tc.index, ToolResult.Builder.failure(
                                tc.name, "Aborted: sibling tool error", null));
                    }
                    continue;
                }

                if (batch.isConcurrent && batch.calls.size() > 1) {
                    executeConcurrentBatch(batch, resultsByIndex, abortSiblings, pool);
                } else {
                    executeSerialBatch(batch, resultsByIndex, abortSiblings);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // Sort results by original index
        List<ToolResult> ordered = new ArrayList<>(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            ToolResult r = resultsByIndex.get(i);
            ordered.add(r != null ? r : ToolResult.Builder.failure(
                    calls.get(i).name, "No result produced", null));
        }
        return ordered;
    }

    // ============ Partitioning (claude-code's partitionToolCalls) ============

    /**
     * Partition tool calls into batches.
     * Consecutive concurrent-safe tools → one batch.
     * Each non-concurrent tool → its own singleton batch.
     */
    List<Batch> partition(List<ToolCall> calls) {
        List<Batch> batches = new ArrayList<>();
        List<ToolCall> current = new ArrayList<>();
        boolean currentIsConcurrent = true;

        for (ToolCall call : calls) {
            boolean safe = isConcurrencySafe(call);

            if (current.isEmpty()) {
                current.add(call);
                currentIsConcurrent = safe;
            } else if (currentIsConcurrent && safe) {
                current.add(call);
            } else {
                // Flush current batch
                batches.add(new Batch(List.copyOf(current), currentIsConcurrent));
                current.clear();
                current.add(call);
                currentIsConcurrent = safe;
            }
        }

        if (!current.isEmpty()) {
            batches.add(new Batch(List.copyOf(current), currentIsConcurrent));
        }

        log.debug("Partitioned {} calls into {} batches (maxConcurrency={})",
                calls.size(), batches.size(), maxConcurrency);
        return batches;
    }

    boolean isConcurrencySafe(ToolCall call) {
        Tool tool = registry.get(call.name);
        if (tool == null) return false;
        return tool.isConcurrencySafe();
    }

    // ============ Batch execution ============

    private void executeConcurrentBatch(Batch batch, Map<Integer, ToolResult> resultsByIndex,
                                         AtomicBoolean abortSiblings, ExecutorService pool) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (ToolCall tc : batch.calls) {
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                if (abortSiblings.get()) {
                    resultsByIndex.put(tc.index, ToolResult.Builder.failure(
                            tc.name, "Aborted: sibling tool error", null));
                    return;
                }
                ToolResult result = baseExecutor.execute(tc.name, tc.arguments);
                resultsByIndex.put(tc.index, result);

                // Error cascading: destructive errors abort siblings
                if (result.isError() && isDestructiveCall(tc)) {
                    abortSiblings.set(true);
                    log.warn("Destructive tool '{}' failed — aborting siblings", tc.name);
                }
            }, pool);
            futures.add(f);
        }

        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(timeoutMs * batch.calls.size(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Concurrent batch timed out after {}ms", timeoutMs * batch.calls.size());
            abortSiblings.set(true);
        } catch (Exception e) {
            log.error("Concurrent batch failed: {}", e.getMessage());
        }
    }

    private void executeSerialBatch(Batch batch, Map<Integer, ToolResult> resultsByIndex,
                                     AtomicBoolean abortSiblings) {
        for (ToolCall tc : batch.calls) {
            if (abortSiblings.get()) {
                resultsByIndex.put(tc.index, ToolResult.Builder.failure(
                        tc.name, "Aborted: sibling tool error", null));
                continue;
            }
            ToolResult result = baseExecutor.execute(tc.name, tc.arguments);
            resultsByIndex.put(tc.index, result);

            if (result.isError() && isDestructiveCall(tc)) {
                abortSiblings.set(true);
                log.warn("Destructive tool '{}' failed — aborting siblings", tc.name);
            }
        }
    }

    private boolean isDestructiveCall(ToolCall call) {
        Tool tool = registry.get(call.name);
        if (tool == null) return false;
        return tool.isDestructive();
    }

    // ============ Types ============

    record Batch(List<ToolCall> calls, boolean isConcurrent) {}

    /**
     * A tool call with its original index for result ordering.
     */
    public static class ToolCall {
        final String name;
        final Map<String, Object> arguments;
        final int index;
        private static final AtomicInteger counter = new AtomicInteger();

        public ToolCall(String name, Map<String, Object> arguments) {
            this.name = name;
            this.arguments = arguments;
            this.index = counter.getAndIncrement();
        }

        public static void resetCounter() { counter.set(0); }
    }
}
