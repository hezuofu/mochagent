// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent.loop;


/**
 * Reflector strategy interface.
 *
 * @author lanxia39@163.com
 */
@FunctionalInterface
public interface Reflector {

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
    static Reflector noop() {

        return (result, critique) -> ImprovementPlan.empty();
    }
}
