package io.sketch.mochaagents.llm.router;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import java.util.Comparator;
import java.util.List;

/**
 * 成本优化器 — 根据 token 用量和任务复杂度选择最具性价比的 LLM.
 * @author lanxia39@163.com
 */
public class CostOptimizer {

    /**
     * 从候选列表中选最优 LLM.
     */
    public LLM select(List<LLM> candidates, LLMRequest request) {
        if (candidates.isEmpty()) throw new IllegalArgumentException("No candidates");
        // 简单策略：选择上下文最大的（能力最强），实际场景按成本加权
        return candidates.stream()
                .max(Comparator.comparingInt(LLM::maxContextTokens))
                .orElse(candidates.get(0));
    }
}
