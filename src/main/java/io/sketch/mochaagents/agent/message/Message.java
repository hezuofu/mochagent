package io.sketch.mochaagents.agent.message;

import java.util.List;
import java.util.Map;

/**
 * Sealed message type hierarchy — mirrors claude-code's types/message.ts.
 * Supports UserMessage, AssistantMessage (with content blocks), and SystemMessage variants.
 * @author lanxia39@163.com
 */
public sealed interface Message
        permits Message.UserMessage, Message.AssistantMessage, Message.SystemMessage {

    String role();

    /** User input — may contain text + embedded tool_result blocks. */
    record UserMessage(String content, List<ContentBlock> toolResults) implements Message {
        public UserMessage(String content) { this(content, List.of()); }
        @Override public String role() { return "user"; }
    }

    /**
     * Model output — contains typed content blocks: text, tool_use, thinking.
     * Maps to claude-code's AssistantMessage with ContentBlock[].
     */
    record AssistantMessage(List<ContentBlock> content, String model, TokenUsage usage) implements Message {
        public AssistantMessage(List<ContentBlock> content) { this(content, null, null); }
        @Override public String role() { return "assistant"; }

        /** Flatten all text blocks into a single string. */
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (ContentBlock b : content) {
                if (b instanceof ContentBlock.TextBlock t) sb.append(t.text());
                else if (b instanceof ContentBlock.ThinkingBlock t) sb.append("[Thinking: ").append(t.thought()).append("]");
                else if (b instanceof ContentBlock.ToolUseBlock t) sb.append("[Tool: ").append(t.name()).append("(").append(t.input()).append(")]");
            }
            return sb.toString();
        }
    }

    /** System messages — prompt, compact boundary, api error, progress. */
    sealed interface SystemMessage extends Message {
        @Override default String role() { return "system"; }
        String content();

        record Prompt(String content) implements SystemMessage {}
        record CompactBoundary(String content, int preCompactTokens, int postCompactTokens) implements SystemMessage {}
        record ApiError(String content, int statusCode) implements SystemMessage {}
        record Progress(String content, double percent) implements SystemMessage {}
    }

    /** Token usage statistics from API response. */
    record TokenUsage(int inputTokens, int outputTokens) {
        public int total() { return inputTokens + outputTokens; }
    }
}
