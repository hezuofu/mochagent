package io.sketch.mochaagents.context.strategy;

import io.sketch.mochaagents.context.ContextChunk;
import io.sketch.mochaagents.context.ContextStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * 混合上下文策略 — 结合系统消息保留与滑动窗口.
 * @author lanxia39@163.com
 */
public class HybridContextStrategy implements ContextStrategy {

    @Override
    public List<ContextChunk> apply(List<ContextChunk> chunks, int maxTokens) {
        if (chunks.isEmpty()) return chunks;

        int recentBudget = (int) (maxTokens * 0.8);
        int summaryBudget = maxTokens - recentBudget;

        List<ContextChunk> systemChunks = new ArrayList<>();
        int summaryTokens = 0;
        for (ContextChunk chunk : chunks) {
            if ("system".equals(chunk.role()) && summaryTokens + chunk.tokenCount() <= summaryBudget) {
                systemChunks.add(chunk);
                summaryTokens += chunk.tokenCount();
            }
        }

        // Recent chunks (sliding window, reverse order then restore)
        List<ContextChunk> recent = new ArrayList<>();
        int recentTokens = 0;
        for (int i = chunks.size() - 1; i >= 0; i--) {
            ContextChunk chunk = chunks.get(i);
            if (!"system".equals(chunk.role()) && recentTokens + chunk.tokenCount() <= recentBudget) {
                recentTokens += chunk.tokenCount();
                recent.add(0, chunk); // maintain order
            }
        }

        // Merge: system first, then recent
        List<ContextChunk> result = new ArrayList<>(systemChunks);
        result.addAll(recent);
        return result;
    }
}
