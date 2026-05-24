// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.context;

import io.sketch.mochaagents.context.ContextChunk;
import io.sketch.mochaagents.context.ContextStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * 滑动窗口策略 — 保留最近 N 个 token 的上下文块.
 * @author lanxia39@163.com
 */
public class SlidingWindowStrategy implements ContextStrategy {

    @Override
    public List<ContextChunk> apply(List<ContextChunk> chunks, int maxTokens) {
        List<ContextChunk> result = new ArrayList<>();
        int tokenSum = 0;
        for (int i = chunks.size() - 1; i >= 0; i--) {
            ContextChunk chunk = chunks.get(i);
            if (tokenSum + chunk.tokenCount() <= maxTokens) {
                result.add(0, chunk);
                tokenSum += chunk.tokenCount();
            } else {
                break;
            }
        }
        return result;
    }
}
