package io.sketch.mochaagents.core.loop;

import io.sketch.mochaagents.core.Agent;

/**
 * 自主循环接口 — Agent "思考-行动-观察" 的主循环.
 *
 * <p>封装 ReAct / OPAR / TAO 等策略的执行生命周期.
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public interface AgenticLoop<I, O> {

    /**
     * 启动循环.
     *
     * @param agent   执行 Agent
     * @param input   初始输入
     * @param condition 终止条件
     * @return 最终输出
     */
    O run(Agent<I, O> agent, I input, TerminationCondition condition);

    /**
     * 执行单步.
     *
     * @param agent    执行 Agent
     * @param input    当前输入
     * @param stepNum  当前步号
     * @return 步骤结果
     */
    StepResult step(Agent<I, O> agent, I input, int stepNum);
}
