// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent.loop.step;

import io.sketch.mochaagents.message.ContentBlock;
import java.util.List;

/**
 * 行动步 — 记录单次 ReAct 循环中的思考-行动-观察.
 *
 * @param stepNumber       当前步号
 * @param modelInput       Model 输入消息
 * @param modelOutput      Model 输出
 * @param action           执行的动作/工具调用
 * @param observation      观察结果
 * @param error            错误信息（若有）
 * @param inputTokens      输入 token 数
 * @param outputTokens     输出 token 数
 * @param isFinalAnswer    是否为最终答案
 * @param assistantBlocks  模型返回的 typed content blocks (ToolUseBlock/TextBlock/ThinkingBlock)
 * @param toolResultBlocks 工具执行结果的 typed blocks (ToolResultBlock)
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
        boolean isFinalAnswer,
        List<ContentBlock> assistantBlocks,
        List<ContentBlock.ToolResultBlock> toolResultBlocks
) implements MemoryStep {

    /** Backward-compatible constructor — typed fields default to empty. */
    public ActionStep(int stepNumber, String modelInput, String modelOutput,
                      String action, String observation, String error,
                      int inputTokens, int outputTokens, boolean isFinalAnswer) {
        this(stepNumber, modelInput, modelOutput, action, observation, error,
             inputTokens, outputTokens, isFinalAnswer, List.of(), List.of());
    }

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

    /** True when this step carries typed content blocks (from native tool calling). */
    public boolean hasTypedContent() {
        return !assistantBlocks.isEmpty();
    }
}
