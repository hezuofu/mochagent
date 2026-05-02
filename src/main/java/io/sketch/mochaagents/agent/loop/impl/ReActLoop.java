package io.sketch.mochaagents.agent.loop.impl;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.agent.loop.AgenticLoop;
import io.sketch.mochaagents.agent.loop.StepResult;
import io.sketch.mochaagents.agent.loop.TerminationCondition;
import io.sketch.mochaagents.memory.AgentMemory;

/**
 * ReAct 循环 — {@link AgenticLoop} 的具体实现，执行 "思考→行动→观察" 迭代.
 *
 * <p>循环流程：
 * <ol>
 *   <li>生成/更新计划（可选）</li>
 *   <li>调用 LLM 生成行动</li>
 *   <li>执行工具/代码</li>
 *   <li>记录观察结果</li>
 *   <li>判断是否终止</li>
 * </ol>
 *
 * <p>依赖两个函数式接口注入：
 * <ul>
 *   <li>{@code planningFn} — 生成计划步骤</li>
 *   <li>{@code stepFn} — 执行单步（LLM 调用 + 工具/代码执行）</li>
 * </ul>
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public class ReActLoop<I, O> implements AgenticLoop<I, O> {

    /**
     * 单步执行函数 — 一次 ReAct 迭代的完整实现.
     *
     * <p>输入 stepNumber 和 memory，返回 StepResult.
     */
    @FunctionalInterface
    public interface StepExecutor<I> {
        StepResult execute(int stepNumber, I input, AgentMemory memory);
    }

    /**
     * 规划函数 — 生成或更新计划.
     *
     * <p>返回规划步骤信息字符串，null 表示跳过规划.
     */
    @FunctionalInterface
    public interface PlanningFn<I> {
        String plan(int stepNumber, I input, AgentMemory memory);
    }

    private final PlanningFn<I> planningFn;
    private final StepExecutor<I> stepExecutor;
    private final int planningInterval;

    /**
     * @param planningFn     规划函数，可为 null 跳过规划
     * @param stepExecutor   单步执行函数
     * @param planningInterval 规划间隔（每隔多少步规划一次，0 或 null 表示不规划）
     */
    public ReActLoop(PlanningFn<I> planningFn, StepExecutor<I> stepExecutor, int planningInterval) {
        this.planningFn = planningFn;
        this.stepExecutor = stepExecutor;
        this.planningInterval = planningInterval;
    }

    public ReActLoop(StepExecutor<I> stepExecutor) {
        this(null, stepExecutor, 0);
    }

    @Override
    public O run(Agent<I, O> agent, I input, TerminationCondition condition) {
        // 使用 Agent 内部的 memory
        AgentMemory memory = getMemory(agent);
        if (memory != null) {
            memory.setSystemPrompt(buildSystemPrompt(agent));
        }

        int step = 1;
        StepResult result;
        do {
            // 可选规划
            if (planningFn != null && planningInterval > 0
                    && (step == 1 || (step - 1) % planningInterval == 0)) {
                String planText = planningFn.plan(step, input, memory);
                if (planText != null && memory != null) {
                    memory.appendPlanning(planText, "", 0, 0);
                }
            }

            // 执行单步
            result = stepExecutor.execute(step, input, memory);

            step++;

        } while (!condition.shouldTerminate(result) && !isFinalAnswer(memory));

        @SuppressWarnings("unchecked")
        O output = (O) result.output();
        return output;
    }

    @Override
    public StepResult step(Agent<I, O> agent, I input, int stepNum) {
        AgentMemory memory = getMemory(agent);
        return stepExecutor.execute(stepNum, input, memory);
    }

    // ============ 辅助方法 ============

    @SuppressWarnings("unchecked")
    private AgentMemory getMemory(Agent<I, O> agent) {
        try {
            var method = agent.getClass().getMethod("memory");
            return (AgentMemory) method.invoke(agent);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String buildSystemPrompt(Agent<I, O> agent) {
        try {
            var method = agent.getClass().getMethod("buildSystemPrompt");
            return (String) method.invoke(agent);
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isFinalAnswer(AgentMemory memory) {
        return memory != null && memory.hasFinalAnswer();
    }
}
