package io.sketch.mochaagents.context.strategy;

import io.sketch.mochaagents.context.ContextChunk;
import io.sketch.mochaagents.context.ContextStrategy;
import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 混合上下文策略 — 系统消息保留 + LLM 摘要溢出 + 滑动窗口保留最近消息.
 *
 * <p>含 LLM 时: 20% 预算用于 LLM 摘要旧消息, 80% 滑动窗口保留最近.
 * <p>无 LLM 时: 20% 预算保留系统消息, 80% 滑动窗口.
 * @author lanxia39@163.com
 */
public class HybridContextStrategy implements ContextStrategy {

    private static final Logger log = LoggerFactory.getLogger(HybridContextStrategy.class);
    private static final int SUMMARY_MAX_TOKENS = 200;

    private final LLM llm;

    public HybridContextStrategy() { this(null); }

    public HybridContextStrategy(LLM llm) { this.llm = llm; }

    @Override
    public List<ContextChunk> apply(List<ContextChunk> chunks, int maxTokens) {
        if (chunks.isEmpty()) return chunks;

        int recentBudget = (int) (maxTokens * 0.8);
        int summaryBudget = maxTokens - recentBudget;

        // Phase 1: collect system messages
        List<ContextChunk> systemChunks = new ArrayList<>();
        int systemTokens = 0;
        for (ContextChunk c : chunks) {
            if ("system".equals(c.role()) && systemTokens + c.tokenCount() <= summaryBudget) {
                systemChunks.add(c);
                systemTokens += c.tokenCount();
            }
        }

        // Phase 2: collect recent non-system chunks (sliding window from tail)
        List<ContextChunk> recent = new ArrayList<>();
        int recentTokens = 0;
        int overflowIdx = 0;
        for (int i = chunks.size() - 1; i >= 0; i--) {
            ContextChunk c = chunks.get(i);
            if (!"system".equals(c.role())) {
                if (recentTokens + c.tokenCount() <= recentBudget) {
                    recentTokens += c.tokenCount();
                    recent.add(0, c);
                } else {
                    overflowIdx = Math.max(overflowIdx, i + 1);
                    break;
                }
            }
        }

        // Phase 3: if we have LLM and overflow, summarize it
        if (llm != null && overflowIdx > 0) {
            List<ContextChunk> overflow = chunks.subList(0, overflowIdx);
            String summary = summarizeOverflow(overflow);
            int summaryTokens = Math.max(1, summary.length() / 4);

            List<ContextChunk> result = new ArrayList<>();
            result.add(new ContextChunk("hybrid-summary-" + UUID.randomUUID().toString().substring(0, 8),
                    "system", summary, summaryTokens));
            result.addAll(systemChunks);
            result.addAll(recent);
            log.debug("HybridContextStrategy (LLM): {} overflow chunks summarized to {} tokens",
                    overflow.size(), summaryTokens);
            return result;
        }

        // Phase 4: no LLM — merge system + recent
        List<ContextChunk> result = new ArrayList<>(systemChunks);
        result.addAll(recent);
        return result;
    }

    private String summarizeOverflow(List<ContextChunk> overflow) {
        StringBuilder content = new StringBuilder();
        for (ContextChunk c : overflow) {
            String prefix = switch (c.role()) {
                case "user" -> "User: ";
                case "assistant" -> "Assistant: ";
                case "system" -> "System: ";
                default -> c.role() + ": ";
            };
            content.append(prefix).append(c.content()).append("\n");
        }

        String prompt = """
                Summarize this conversation history concisely.
                Include key facts, decisions, and user intent.
                Keep under %d words. Output only the summary.

                ---
                %s
                ---
                Summary:""".formatted(SUMMARY_MAX_TOKENS / 4 * 3, content.toString());

        try {
            return "[Conversation summary]\n" + llm.complete(LLMRequest.builder()
                    .addMessage("user", prompt)
                    .maxTokens(SUMMARY_MAX_TOKENS)
                    .temperature(0.2)
                    .build()).content().trim();
        } catch (Exception e) {
            log.warn("Hybrid LLM summary failed: {}", e.getMessage());
            return "[Earlier context: " + overflow.size() + " messages omitted]";
        }
    }
}
