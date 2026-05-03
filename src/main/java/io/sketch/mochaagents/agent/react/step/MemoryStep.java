package io.sketch.mochaagents.agent.react.step;

/**
 * Agent 执行步 — sealed 接口，定义 ReAct 循环中记录的步骤类型.
 *
 * <p>三种实现对应 smolagents 的步记录体系:
 * <ul>
 *   <li>{@link ContentStep} — 内容步（系统提示 / 任务 / 最终答案）</li>
 *   <li>{@link PlanningStep} — 规划步骤</li>
 *   <li>{@link ActionStep} — 行动步骤</li>
 * </ul>
 */
public sealed interface MemoryStep
        permits ContentStep, PlanningStep, ActionStep {

    /** 步类型标识. */
    String type();
}
