// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.orchestration;

import io.sketch.mochaagents.agent.Agent;

/**
 * Single-task execution strategy — purely executes one task, no graph traversal.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@code DirectExecutor} — direct agent call</li>
 *   <li>{@code RetryingExecutor} — decorator with retry + timeout</li>
 *   <li>{@code MatchingExecutor} — decorator matching worker by capability</li>
 * </ul>
 *
 * @author lanxia39@163.com
 */
@FunctionalInterface
public interface TaskExecutor {

    Object execute(String taskDescription, Agent<?, ?> worker);

    // ── Implementations ──

    /** Direct execution — calls worker.execute(task). */
    @SuppressWarnings("unchecked")
    static TaskExecutor direct() {
        return (task, worker) -> ((Agent<String, Object>) (Object) worker).execute(task);
    }

    /** Decorate with retry + timeout. */
    static TaskExecutor retrying(TaskExecutor delegate, ExecutionPolicy policy) {
        return (task, worker) -> {
            Exception last = null;
            for (int i = 0; i <= policy.maxRetries(); i++) {
                try {
                    return delegate.execute(task, worker);
                } catch (Exception e) {
                    last = e;
                    if (i < policy.maxRetries()) {
                        try { Thread.sleep(1000L * (i + 1)); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                }
            }
            throw new RuntimeException("Task failed after " + (policy.maxRetries() + 1) + " attempts", last);
        };
    }

}
