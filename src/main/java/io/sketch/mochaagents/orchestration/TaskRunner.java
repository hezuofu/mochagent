// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.orchestration;

import io.sketch.mochaagents.agent.Agent;

/**
 * Executes a single orchestration task on a worker agent.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link DirectRunner} — direct agent call</li>
 *   <li>{@link RetryingRunner} — decorator: retry + timeout</li>
 * </ul>
 *
 * @author lanxia39@163.com
 */
@FunctionalInterface
public interface TaskRunner {

    Object run(String taskDescription, Agent<?, ?> worker);
}
