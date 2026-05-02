package io.sketch.mochaagents.context.strategy;

import io.sketch.mochaagents.context.ContextChunk;
import io.sketch.mochaagents.context.ContextStrategy;

import java.util.List;

/**
 * 摘要策略 — 保留系统提示和最近消息，中间用摘要替代.
 */
public class SummarizationStrategy implements ContextStrategy {

    @Override
    public List<ContextChunk> apply(List<ContextChunk> chunks, int maxTokens) {
        if (chunks.isEmpty()) return chunks;

        int tokenSum = 0;
        int keepFrom = chunks.size();

        // 从尾部保留，直到超出限制
        for (int i = chunks.size() - 1; i >= 0; i--) {
            if (tokenSum + chunks.get(i).tokenCount() > maxTokens) {
                keepFrom = i + 1;
                break;
            }
            tokenSum += chunks.get(i).tokenCount();
        }

        // 添加摘要标记
        if (keepFrom > 0) {
            ContextChunk summaryChunk = new ContextChunk(
                    "summary", "system",
                    "[Earlier context summarized: " + keepFrom + " messages omitted]",
                    10);
            List<ContextChunk> result = new java.util.ArrayList<>();
            result.add(summaryChunk);
            result.addAll(chunks.subList(keepFrom, chunks.size()));
            return result;
        }
        return chunks;
    }
}
