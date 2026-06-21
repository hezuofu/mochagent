// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.plan;

/**
 * Minimal planner — decompose a goal into executable steps.
  * @author lanxia39@163.com
 */
@FunctionalInterface
public interface Planner<T> {

    Plan<T> generatePlan(PlanningRequest<T> request);

    default Plan<T> replan(Plan<T> current, ExecutionFeedback feedback) {
        return feedback.shouldReplan() ? generatePlan(PlanningRequest.<T>builder()
                .goal(current.getGoal()).build()) : current;
    }
}
