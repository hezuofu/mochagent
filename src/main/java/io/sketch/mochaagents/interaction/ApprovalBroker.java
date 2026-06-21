// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.interaction;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Approval broker — hermes-agent callback pattern for external approval.
 *
 * <p>Multiple handlers race to resolve: first response wins. Thread-safe.
 *
 * <pre>{@code
 * ApprovalBroker broker = new ApprovalBroker();
 * broker.register((use, session) -> {
 *     // CLI: show dialog, return decision
 *     return CompletableFuture.completedFuture(Decision.allow("user approved"));
 * });
 * }</pre>
 *
 * @author lanxia39@163.com
 */
public class ApprovalBroker {

    private final CopyOnWriteArrayList<Handler> handlers = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, CompletableFuture<Decision>> pending = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface Handler {
        CompletableFuture<Decision> approve(ToolUse use, String sessionId);
    }

    public void register(Handler handler) { handlers.add(handler); }

    /** Request approval. Races all handlers — first to resolve wins. */
    public CompletableFuture<Decision> request(ToolUse use, String sessionId) {
        if (handlers.isEmpty()) {
            return CompletableFuture.completedFuture(Decision.deny("No approval handler"));
        }

        CompletableFuture<Decision> result = new CompletableFuture<>();
        for (Handler h : handlers) {
            h.approve(use, sessionId).thenAccept(d -> {
                if (!result.isDone()) {
                    result.complete(d);
                }
            });
        }
        // Timeout after 60s if no handler responds
        CompletableFuture.delayedExecutor(60, java.util.concurrent.TimeUnit.SECONDS)
                .execute(() -> {
                    if (!result.isDone()) {
                        result.complete(Decision.deny("approval timeout"));
                    }
                });
        return result;
    }

    /** Direct injection — for tests and programmatic approval. */
    public void resolve(String requestId, Decision decision) {
        CompletableFuture<Decision> f = pending.remove(requestId);
        if (f != null) {
            f.complete(decision);
        }
    }
}
