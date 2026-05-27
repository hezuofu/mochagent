// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.orchestration;

import io.sketch.mochaagents.agent.Agent;

/**
 * Orchestration strategy — how multiple agents collaborate.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link DebateStrategy} — debate and consensus</li>
 *   <li>{@link SwarmStrategy} — parallel swarm with consensus</li>
 * </ul>
 *
 * @author lanxia39@163.com
 */
@FunctionalInterface
public interface OrchestrationStrategy {

    <I, O> O execute(AgentTeam team, I input);

    /** Chain agents sequentially — each agent's output becomes the next agent's input. */
    static OrchestrationStrategy sequential() {
        return new OrchestrationStrategy() {
            @Override @SuppressWarnings("unchecked")
            public <I, O> O execute(AgentTeam team, I input) {
                Object result = input;
                for (Agent<?, ?> agent : team.getAgents()) {
                    result = ((Agent<Object, Object>) (Object) agent).execute(result);
                }
                return (O) result;
            }
        };
    }

    /** Run all agents on the same input in parallel, returning collected results. */
    static OrchestrationStrategy parallel() {
        return new OrchestrationStrategy() {
            @Override @SuppressWarnings("unchecked")
            public <I, O> O execute(AgentTeam team, I input) {
                return (O) team.getAgents().stream()
                        .map(a -> ((Agent<Object, Object>) (Object) a).execute(input))
                        .toList();
            }
        };
    }
}
