package io.sketch.mochaagents.plan;

/**
 * Minimal planner — decompose a goal into executable steps.
 */
@FunctionalInterface
public interface Planner<T> {

    Plan<T> generatePlan(PlanningRequest<T> request);

    default Plan<T> replan(Plan<T> current, ExecutionFeedback feedback) {
        return feedback.shouldReplan() ? generatePlan(PlanningRequest.<T>builder()
                .goal(current.getGoal()).build()) : current;
    }
}
