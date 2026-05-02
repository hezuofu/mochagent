package io.sketch.mochaagents.plan;

/**
 * 规划策略 — 函数式接口.
 */
@FunctionalInterface
public interface PlanningStrategy {
    Plan<?> plan(PlanningRequest<?> request);
    default String name() { return getClass().getSimpleName(); }
}
