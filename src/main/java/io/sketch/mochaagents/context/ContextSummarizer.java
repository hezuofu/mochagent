package io.sketch.mochaagents.context;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Shared utility for LLM-based context summarization.
 * Used by context strategies and compressors to avoid code duplication.
 *
 * @author lanxia39@163.com
 */
public final class ContextSummarizer {

    private static final Logger log = LoggerFactory.getLogger(ContextSummarizer.class);
    private static final int DEFAULT_MAX_TOKENS = 200;

    private ContextSummarizer() {}

    /** Build a conversation transcript from chunks for LLM summarization. */
    public static String buildTranscript(List<ContextChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (ContextChunk c : chunks) {
            String prefix = switch (c.role()) {
                case "user" -> "User: ";
                case "assistant" -> "Assistant: ";
                case "system" -> "System: ";
                default -> c.role() + ": ";
            };
            sb.append(prefix).append(c.content()).append("\n");
        }
        return sb.toString();
    }

    /** Use LLM to summarize conversation context. Falls back to placeholder on failure. */
    public static String summarize(LLM llm, List<ContextChunk> overflow, int maxTokens) {
        String transcript = buildTranscript(overflow);
        int words = maxTokens / 4 * 3;

        try {
            String result = llm.complete(LLMRequest.builder()
                    .addMessage("user", String.format("""
                            Summarize this conversation history concisely.
                            Include key facts, decisions, and the user's intent.
                            Keep under %d words. Output only the summary.

                            ---
                            %s
                            ---
                            Summary:""", words, transcript))
                    .maxTokens(maxTokens)
                    .temperature(0.2)
                    .build()).content().trim();

            return result;
        } catch (Exception e) {
            log.warn("LLM summarization failed: {}", e.getMessage());
            return "[Earlier context: " + overflow.size() + " messages omitted]";
        }
    }
}
