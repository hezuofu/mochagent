// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.context;

import io.sketch.mochaagents.model.Model;
import io.sketch.mochaagents.model.ModelRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Model 上下文压缩器 — 将旧消息用 Model 压缩为语义摘要，节省 token 预算.
 *
 * <p>压缩策略：从尾部保留最近 50% 的消息，其余用 Model 生成摘要替代.
 * @author lanxia39@163.com
 */
public class ModelContextCompressor implements ContextCompressor {

    private static final Logger log = LoggerFactory.getLogger(ModelContextCompressor.class);
    private static final int SUMMARY_MAX_TOKENS = 256;

    private final Model model;
    private final double keepRatio;

    public ModelContextCompressor(Model model) { this(model, 0.5); }

    public ModelContextCompressor(Model model, double keepRatio) {
        this.model = model;
        this.keepRatio = Math.min(0.9, Math.max(0.1, keepRatio));
    }

    @Override
    public List<ContextChunk> compress(List<ContextChunk> chunks, int maxTokens) {
        if (chunks.isEmpty()) return chunks;

        int totalTokens = chunks.stream().mapToInt(ContextChunk::tokenCount).sum();
        if (totalTokens <= maxTokens) return chunks;

        // Split: keep the most recent (keepRatio * chunk count), summarize the rest
        int splitIdx = (int) (chunks.size() * (1.0 - keepRatio));
        if (splitIdx <= 0) return chunks;

        List<ContextChunk> toSummarize = chunks.subList(0, splitIdx);
        List<ContextChunk> toKeep = chunks.subList(splitIdx, chunks.size());

        String summary = "[Compressed context]\n"
                + ContextSummarizer.summarize(model, toSummarize, SUMMARY_MAX_TOKENS);

        List<ContextChunk> result = new ArrayList<>();
        result.add(new ContextChunk("compressed-" + UUID.randomUUID().toString().substring(0, 8),
                "system", summary,
                Math.max(1, summary.length() / 4)));
        result.addAll(toKeep);

        log.debug("ModelContextCompressor: {} chunks → summary ({} chars) + {} recent chunks",
                toSummarize.size(), summary.length(), toKeep.size());
        return result;
    }
}
