// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.plan;

import java.util.List;

/**
 * 任务分解器接口.
 * @author lanxia39@163.com
 */
public interface TaskDecomposer {

    List<PlanStep> decompose(String task, int maxSteps);
}
