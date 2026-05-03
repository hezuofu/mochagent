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
 * 摘要策略 — 用 LLM 将超出上下文窗口的旧消息压缩为语义摘要，
 * 保留系统提示和最近消息，LLM 可看到历史要点而非空白占位符.
 * @author lanxia39@163.com
 */
public class SummarizationStrategy implements ContextStrategy {

    private static final Logger log = LoggerFactory.getLogger(SummarizationStrategy.class);
    private static final int SUMMARY_MAX_TOKENS = 200;

    private final LLM llm;
    private final int summaryBudgetTokens;

    public SummarizationStrategy(LLM llm) {
        this(llm, SUMMARY_MAX_TOKENS);
    }

    public SummarizationStrategy(LLM llm, int summaryBudgetTokens) {
        this.llm = llm;
        this.summaryBudgetTokens = summaryBudgetTokens;
    }

    @Override
    public List<ContextChunk> apply(List<ContextChunk> chunks, int maxTokens) {
        if (chunks.isEmpty()) return chunks;

        int totalTokens = chunks.stream().mapToInt(ContextChunk::tokenCount).sum();
        if (totalTokens <= maxTokens) return chunks;

        // Reserve budget for the summary chunk itself
        int keepBudget = maxTokens - summaryBudgetTokens;
        if (keepBudget <= 0) keepBudget = maxTokens / 2;

        // Partition: walk from the end to find which chunks fit in keepBudget
        int keepTokens = 0;
        int splitIdx = chunks.size();
        for (int i = chunks.size() - 1; i >= 0; i--) {
            int t = chunks.get(i).tokenCount();
            if (keepTokens + t > keepBudget) {
                splitIdx = i + 1;
                break;
            }
            keepTokens += t;
        }

        // No overflow to summarize
        if (splitIdx == 0) return chunks;

        List<ContextChunk> overflow = chunks.subList(0, splitIdx);
        List<ContextChunk> kept = chunks.subList(splitIdx, chunks.size());

        // Call LLM to summarize the overflow
        String summary = summarizeOverflow(overflow);
        int summaryTokens = Math.max(1, summary.length() / 4);

        List<ContextChunk> result = new ArrayList<>();
        result.add(new ContextChunk("summary-" + UUID.randomUUID().toString().substring(0, 8),
                "system", summary, summaryTokens));
        result.addAll(kept);

        log.debug("SummarizationStrategy: {} chunks overflow → {} token summary, kept {} recent chunks",
                overflow.size(), summaryTokens, kept.size());
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
                Summarize the following conversation history concisely.
                Include key facts, decisions, and the userʼs intent.
                Keep the summary under %d words. Output only the summary, no preamble.

                ---
                %s
                ---
                Summary:""".formatted(summaryBudgetTokens / 4 * 3, content.toString());

        try {
            String response = llm.complete(LLMRequest.builder()
                    .addMessage("user", prompt)
                    .maxTokens(summaryBudgetTokens)
                    .temperature(0.3)
                    .build()).content();

            return "[Earlier conversation summary]\n" + response.trim();
        } catch (Exception e) {
            log.warn("Summarization LLM call failed, falling back to truncation: {}", e.getMessage());
            return "[Earlier context: " + overflow.size() + " messages omitted]";
        }
    }
}
