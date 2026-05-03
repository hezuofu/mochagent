package io.sketch.mochaagents.plan.strategy;

import io.sketch.mochaagents.plan.Plan;
import io.sketch.mochaagents.plan.PlanningRequest;
import io.sketch.mochaagents.plan.PlanningStrategy;

/**
 * 自适应规划策略 — 根据运行时反馈动态调整.
 * @author lanxia39@163.com
 */
public class AdaptivePlanner implements PlanningStrategy {

    @Override
    public Plan<?> plan(PlanningRequest<?> request) {
        return new DefaultPlan<>(request);
    }
}
