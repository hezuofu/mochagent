package io.sketch.mochaagents.agent.react.step;

/**
 * 行动步 — 记录单次 ReAct 循环中的思考-行动-观察.
 *
 * @param stepNumber     当前步号
 * @param modelInput     LLM 输入消息
 * @param modelOutput    LLM 输出
 * @param action         执行的动作/工具调用
 * @param observation    观察结果
 * @param error          错误信息（若有）
 * @param inputTokens    输入 token 数
 * @param outputTokens   输出 token 数
 * @param isFinalAnswer  是否为最终答案
 * @author lanxia39@163.com
 */
public record ActionStep(
        int stepNumber,
        String modelInput,
        String modelOutput,
        String action,
        String observation,
        String error,
        int inputTokens,
        int outputTokens,
        boolean isFinalAnswer
) implements MemoryStep {

    @Override
    public String type() { return "action"; }

    /** 创建初始空的 ActionStep. */
    public static ActionStep empty(int stepNumber) {
        return new ActionStep(stepNumber, "", "", "", "", null, 0, 0, false);
    }

    /** 返回是否出错. */
    public boolean hasError() {
        return error != null && !error.isEmpty();
    }
}
