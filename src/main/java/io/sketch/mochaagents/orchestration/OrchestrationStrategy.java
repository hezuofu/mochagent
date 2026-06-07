// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.orchestration;

import io.sketch.mochaagents.agent.Agent;

/**
 * Orchestration strategy — how multiple agents collaborate.
 *
 * <p>All strategies accept an optional {@link TaskRunner} for retry/timeout
 * on individual agent calls. Without a runner, agents are called directly.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link DebateStrategy} — debate and consensus</li>
 *   <li>{@link SwarmStrategy} — parallel swarm with consensus</li>
 *   <li>{@code sequential()} — chain agents</li>
 *   <li>{@code parallel()} — fan-out</li>
 * </ul>
 *
 * @author lanxia39@163.com
 */
@FunctionalInterface
public interface OrchestrationStrategy {

    <I, O> O execute(AgentTeam team, I input, TaskRunner runner);

    /** Convenience: execute with a direct (no-retry) runner. */
    default <I, O> O execute(AgentTeam team, I input) {
        return execute(team, input, new DirectRunner());
    }

    // ── Built-in strategies ──

    /** Chain agents sequentially — each agent's output becomes the next agent's input. */
    static OrchestrationStrategy sequential() {
        return new OrchestrationStrategy() {
            @Override
            @SuppressWarnings("unchecked")
            public <I, O> O execute(AgentTeam team, I input, TaskRunner runner) {
                Object result = input;
                for (Agent<?, ?> agent : team.getAgents()) {
                    String task = result != null ? result.toString() : "";
                    result = runner.run(task, agent);
                }
                return (O) result;
            }
        };
    }

    /** Run all agents on the same input in parallel, returning collected results. */
    static OrchestrationStrategy parallel() {
        return new OrchestrationStrategy() {
            @Override
            @SuppressWarnings("unchecked")
            public <I, O> O execute(AgentTeam team, I input, TaskRunner runner) {
                String task = input != null ? input.toString() : "";
                return (O) team.getAgents().stream()
                        .map(agent -> runner.run(task, agent))
                        .toList();
            }
        };
    }
}
