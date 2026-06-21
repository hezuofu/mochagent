// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent;

import io.sketch.mochaagents.agent.loop.StepResult;
import io.sketch.mochaagents.agent.loop.Termination;

/**
 * Agent execution loop with unified termination.
 *
 * @param <I> input type
 * @param <O> output type
 * @author lanxia39@163.com
 */
@FunctionalInterface
public interface AgentLoop<I, O> {

    O run(Agent<I, O> agent, I input, Termination done);
}
