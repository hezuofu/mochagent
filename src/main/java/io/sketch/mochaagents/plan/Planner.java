package io.sketch.mochaagents.plan;

import java.util.concurrent.CompletableFuture;

/**
 * 规划器接口 — 负责任务分解与计划生成.
 *
 * @param <T> 目标类型
 * @author lanxia39@163.com
 */
public interface Planner<T> {

    Plan<T> generatePlan(PlanningRequest<T> request);

    CompletableFuture<Plan<T>> generatePlanAsync(PlanningRequest<T> request);

    Plan<T> replan(Plan<T> currentPlan, ExecutionFeedback feedback);

    PlanningStrategy getStrategy();

    void setStrategy(PlanningStrategy strategy);
}
