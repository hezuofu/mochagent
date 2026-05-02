package io.sketch.mochaagents.agent.loop.reflection;

import io.sketch.mochaagents.agent.loop.StepResult;

/**
 * 反思引擎接口 — 评估单步执行结果，生成改进计划.
 */
@FunctionalInterface
public interface ReflectionEngine {

    /**
     * 反思当前步骤并生成改进计划.
     *
     * @param result   当前步骤结果
     * @param critique 自我批评
     * @return 改进计划
     */
    ImprovementPlan reflect(StepResult result, SelfCritique critique);

    /**
     * 默认实现: 无改进.
     */
    static ReflectionEngine noop() {
        return (result, critique) -> ImprovementPlan.empty();
    }
}
