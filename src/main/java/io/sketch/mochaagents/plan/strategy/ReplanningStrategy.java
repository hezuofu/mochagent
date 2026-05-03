package io.sketch.mochaagents.plan.strategy;

import io.sketch.mochaagents.plan.Plan;
import io.sketch.mochaagents.plan.PlanningRequest;
import io.sketch.mochaagents.plan.PlanningStrategy;

/**
 * 重规划策略 — 失败时完全重新规划.
 * @author lanxia39@163.com
 */
public class ReplanningStrategy implements PlanningStrategy {

    @Override
    public Plan<?> plan(PlanningRequest<?> request) {
        return new DefaultPlan<>(request);
    }
}
