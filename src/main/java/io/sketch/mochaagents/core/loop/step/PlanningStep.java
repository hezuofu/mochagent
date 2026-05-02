package io.sketch.mochaagents.core.loop.step;

/**
 * 规划步 — 记录 Agent 在规划阶段生成的计划.
 *
 * @param plan        计划文本内容
 * @param modelOutput LLM 原始输出
 * @param inputTokens 输入 token 数
 * @param outputTokens 输出 token 数
 */
public record PlanningStep(
        String plan,
        String modelOutput,
        int inputTokens,
        int outputTokens
) implements MemoryStep {

    @Override
    public String type() { return "planning"; }

    /** 创建无 token 统计的规划步. */
    public static PlanningStep of(String plan, String modelOutput) {
        return new PlanningStep(plan, modelOutput, 0, 0);
    }
}
