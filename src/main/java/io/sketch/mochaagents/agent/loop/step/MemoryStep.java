package io.sketch.mochaagents.agent.loop.step;

/**
 * Agent 执行步 — sealed 接口，定义 ReAct 循环中记录的步骤类型.
 *
 * <p>五种实现对应 smolagents 的步记录体系:
 * <ul>
 *   <li>{@link SystemPromptStep} — 系统提示</li>
 *   <li>{@link TaskStep} — 任务输入</li>
 *   <li>{@link PlanningStep} — 规划步骤</li>
 *   <li>{@link ActionStep} — 行动步骤</li>
 *   <li>{@link FinalAnswerStep} — 最终答案</li>
 * </ul>
 */
public sealed interface MemoryStep
        permits SystemPromptStep, TaskStep, PlanningStep, ActionStep, FinalAnswerStep {

    /** 步类型标识. */
    String type();
}
