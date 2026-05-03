package io.sketch.mochaagents.context;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * LLM 上下文压缩器 — 将旧消息用 LLM 压缩为语义摘要，节省 token 预算.
 *
 * <p>压缩策略：从尾部保留最近 50% 的消息，其余用 LLM 生成摘要替代.
 * @author lanxia39@163.com
 */
public class LLMContextCompressor implements ContextCompressor {

    private static final Logger log = LoggerFactory.getLogger(LLMContextCompressor.class);
    private static final int SUMMARY_MAX_TOKENS = 256;

    private final LLM llm;
    private final double keepRatio;

    public LLMContextCompressor(LLM llm) { this(llm, 0.5); }

    public LLMContextCompressor(LLM llm, double keepRatio) {
        this.llm = llm;
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

        // Build conversation text from overflow chunks
        StringBuilder content = new StringBuilder();
        for (ContextChunk c : toSummarize) {
            String prefix = switch (c.role()) {
                case "user" -> "User: ";
                case "assistant" -> "Assistant: ";
                case "system" -> "System: ";
                default -> c.role() + ": ";
            };
            content.append(prefix).append(c.content()).append("\n");
        }

        String summary;
        try {
            summary = llm.complete(LLMRequest.builder()
                    .addMessage("user", """
                            Summarize this conversation history concisely.
                            Keep key facts, decisions made, and the userʼs overall intent.
                            Output only the summary, no more than %d words.

                            ---
                            %s
                            ---
                            Summary:""".formatted(
                            SUMMARY_MAX_TOKENS / 4 * 3, content.toString()))
                    .maxTokens(SUMMARY_MAX_TOKENS)
                    .temperature(0.2)
                    .build()).content().trim();
        } catch (Exception e) {
            log.warn("LLM compression failed, using truncation: {}", e.getMessage());
            summary = "[Earlier context: " + toSummarize.size() + " messages omitted]";
        }

        List<ContextChunk> result = new ArrayList<>();
        result.add(new ContextChunk("compressed-" + UUID.randomUUID().toString().substring(0, 8),
                "system", "[Compressed context]\n" + summary,
                Math.max(1, summary.length() / 4)));
        result.addAll(toKeep);

        log.debug("LLMContextCompressor: {} chunks → summary ({} chars) + {} recent chunks",
                toSummarize.size(), summary.length(), toKeep.size());
        return result;
    }
}
