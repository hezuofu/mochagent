// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Batch tool execution with concurrency-safe partitioning.
 * Claude Code partitionToolCalls pattern: consecutive safe tools → parallel batch.
 *
 * @author lanxia39@163.com
 */
class BatchStrategy implements ToolExecutionStrategy {

    private final ToolRegistry registry;
    private final ToolExecutionStrategy delegate;

    BatchStrategy(ToolRegistry registry, ToolExecutionStrategy delegate) {
        this.registry = registry;
        this.delegate = delegate;
    }

    @Override
    public ToolResult execute(String name, java.util.Map<String, Object> args) {
        return delegate.execute(name, args);
    }

    @Override
    public List<ToolResult> executeBatch(List<ToolCall> calls) {
        if (calls.isEmpty()) {
            return List.of();
        }
        if (calls.size() == 1) {
            return List.of(delegate.execute(calls.get(0).name(), calls.get(0).arguments()));
        }

        var batches = partition(calls);
        var results = new ArrayList<ToolResult>();
        var abort = new AtomicBoolean(false);

        for (var batch : batches) {
            if (abort.get()) {
                for (var tc : batch) {
                    results.add(ToolResult.Builder.failure(tc.name(), "Aborted: sibling error", null));
                }
                continue;
            }
            if (batch.size() > 1 && isConcurrencySafe(batch.get(0).name())) {
                results.addAll(executeParallel(batch, abort));
            } else {
                for (var tc : batch) {
                    if (abort.get()) {
                        results.add(ToolResult.Builder.failure(tc.name(), "Aborted", null));
                    } else {
                        var r = delegate.execute(tc.name(), tc.arguments()); results.add(r);
                        if (r.isError() && isDestructive(tc.name())) {
                            abort.set(true);
                        }
                    }
                }
            }
        }
        return results;
    }

    private List<List<ToolCall>> partition(List<ToolCall> calls) {
        var batches = new ArrayList<List<ToolCall>>();
        var current = new ArrayList<ToolCall>();
        boolean concurrent = true;
        for (var tc : calls) {
            boolean safe = isConcurrencySafe(tc.name());
            if (current.isEmpty()) { current.add(tc); concurrent = safe; }
            else if (concurrent && safe) current.add(tc);
            else { batches.add(List.copyOf(current)); current = new ArrayList<>(); current.add(tc); concurrent = safe; }
        }
        if (!current.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return batches;
    }

    private List<ToolResult> executeParallel(List<ToolCall> batch, AtomicBoolean abort) {
        var results = new ArrayList<ToolResult>();
        var futures = new ArrayList<CompletableFuture<ToolResult>>();
        for (final ToolCall tc : batch) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (abort.get()) {
                    return ToolResult.Builder.failure(tc.name(), "Aborted", null);
                }
                var r = delegate.execute(tc.name(), tc.arguments());
                if (r.isError() && isDestructive(tc.name())) {
                    abort.set(true);
                }
                return r;
            }));
        }
        try { CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(120, TimeUnit.SECONDS); }
        catch (Exception e) { abort.set(true); }
        for (var f : futures) {
            try { results.add(f.getNow(ToolResult.Builder.failure("unknown", "No result", null))); }
            catch (Exception e) { results.add(ToolResult.Builder.failure("unknown", e.getMessage(), null)); }
        }
        return results;
    }

    private boolean isConcurrencySafe(String name) { var t = registry.get(name); return t != null && t.isConcurrencySafe(); }
    private boolean isDestructive(String name) { var t = registry.get(name); return t != null && t.isDestructive(); }
}
