package io.sketch.mochaagents.plan.strategy;

import io.sketch.mochaagents.plan.Plan;
import io.sketch.mochaagents.plan.PlanningRequest;
import io.sketch.mochaagents.plan.PlanningStrategy;

/**
 * 层次规划策略 — 将目标逐层分解为子目标.
 */
public class HierarchicalPlanner implements PlanningStrategy {

    @Override
    public Plan<?> plan(PlanningRequest<?> request) {
        // Stub: 返回一个基础的层次计划
        return new io.sketch.mochaagents.plan.strategy.DefaultPlan<>(request);
    }
}
