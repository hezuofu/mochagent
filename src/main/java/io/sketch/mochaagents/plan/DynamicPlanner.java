package io.sketch.mochaagents.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 动态规划器 — 运行时可根据反馈动态调整计划.
 * @author lanxia39@163.com
 */
public class DynamicPlanner<T> implements Planner<T> {

    private static final Logger log = LoggerFactory.getLogger(DynamicPlanner.class);

    private PlanningStrategy strategy;

    public DynamicPlanner(PlanningStrategy strategy) {
        this.strategy = strategy;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Plan<T> generatePlan(PlanningRequest<T> request) {
        log.info("Generating plan for goal: {}", request.goal());
        Plan<T> plan = (Plan<T>) strategy.plan(request);
        log.debug("Plan generated: {} steps", plan.getSteps().size());
        return plan;
    }

    @Override
    public CompletableFuture<Plan<T>> generatePlanAsync(PlanningRequest<T> request) {
        return CompletableFuture.supplyAsync(() -> generatePlan(request));
    }

    @Override
    public Plan<T> replan(Plan<T> currentPlan, ExecutionFeedback feedback) {
        if (feedback.shouldReplan()) {
            log.info("Replanning due to feedback: {}", feedback.error());
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
