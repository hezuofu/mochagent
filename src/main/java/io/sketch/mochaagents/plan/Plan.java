package io.sketch.mochaagents.plan;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Map;

/**
 * 计划接口 — 任务执行的蓝图.
 *
 * @param <T> 目标类型
 * @author lanxia39@163.com
 */
public interface Plan<T> {
    String getPlanId();
    T getGoal();
    List<PlanStep> getSteps();
    PlanStep getCurrentStep();
    void advance();
    PlanStep.PlanStatus getStatus();
    void setStatus(PlanStep.PlanStatus status);
    DependencyGraph getDependencyGraph();
    long estimateExecutionTime();
    ValidationResult validate();
    String serialize();
}
