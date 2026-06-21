// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.orchestration;

import io.sketch.mochaagents.agent.Agent;

/**
 * Direct task execution — calls worker.execute(task).
 *
 * @author lanxia39@163.com
 */
public final class DirectRunner implements TaskRunner {

    @Override
    @SuppressWarnings("unchecked")
    public Object run(String taskDescription, Agent<?, ?> worker) {
        return ((Agent<String, Object>) (Object) worker).execute(taskDescription);
    }
}
