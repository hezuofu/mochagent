package io.sketch.mochaagents.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 动态规划器 — 运行时可根据反馈动态调整计划.
 */
public class DynamicPlanner<T> implements Planner<T> {

    private PlanningStrategy strategy;

    public DynamicPlanner(PlanningStrategy strategy) {
        this.strategy = strategy;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Plan<T> generatePlan(PlanningRequest<T> request) {
        return (Plan<T>) strategy.plan(request);
    }

    @Override
    public CompletableFuture<Plan<T>> generatePlanAsync(PlanningRequest<T> request) {
        return CompletableFuture.supplyAsync(() -> generatePlan(request));
    }

    @Override
    public Plan<T> replan(Plan<T> currentPlan, ExecutionFeedback feedback) {
        if (feedback.shouldReplan()) {
            PlanningRequest<T> req = PlanningRequest.<T>builder()
                    .goal(currentPlan.getGoal())
                    .build();
            return generatePlan(req);
        }
        return currentPlan;
    }

    @Override
    public PlanningStrategy getStrategy() { return strategy; }

    @Override
    public void setStrategy(PlanningStrategy strategy) { this.strategy = strategy; }
}
