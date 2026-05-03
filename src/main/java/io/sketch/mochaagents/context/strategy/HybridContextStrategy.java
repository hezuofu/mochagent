package io.sketch.mochaagents.context.strategy;

import io.sketch.mochaagents.context.ContextChunk;
import io.sketch.mochaagents.context.ContextStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * 混合上下文策略 — 结合滑动窗口与摘要.
 * @author lanxia39@163.com
 */
public class HybridContextStrategy implements ContextStrategy {

    @Override
    public List<ContextChunk> apply(List<ContextChunk> chunks, int maxTokens) {
        if (chunks.isEmpty()) return chunks;

        // 保留前 20% 作为摘要，后 80% 用滑动窗口
        int recentBudget = (int) (maxTokens * 0.8);
        int summaryBudget = maxTokens - recentBudget;

        List<ContextChunk> result = new ArrayList<>();

        // 摘要部分
        int summaryTokens = 0;
        for (ContextChunk chunk : chunks) {
            if (chunk.role().equals("system") && summaryTokens + chunk.tokenCount() <= summaryBudget) {
                result.add(chunk);
                summaryTokens += chunk.tokenCount();
            }
        }

        // 滑动窗口部分 — 保留最近
        int recentTokens = 0;
        for (int i = chunks.size() - 1; i >= 0; i--) {
            ContextChunk chunk = chunks.get(i);
            if (!chunk.role().equals("system") && recentTokens + chunk.tokenCount() <= recentBudget) {
                result.add(1, chunk);
                recentTokens += chunk.tokenCount();
            }
        }

        return result;
    }
}
