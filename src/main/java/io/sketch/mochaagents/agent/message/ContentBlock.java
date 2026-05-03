package io.sketch.mochaagents.agent.message;

import java.util.Map;

/**
 * Typed content blocks within messages — mirrors claude-code's content block types.
 * Discriminated union: TextBlock | ToolUseBlock | ToolResultBlock | ThinkingBlock.
 * @author lanxia39@163.com
 */
public sealed interface ContentBlock
        permits ContentBlock.TextBlock, ContentBlock.ToolUseBlock,
                ContentBlock.ToolResultBlock, ContentBlock.ThinkingBlock {

    /** Plain text content. */
    record TextBlock(String text) implements ContentBlock {}

    /** Model requests tool execution. */
    record ToolUseBlock(String id, String name, Map<String, Object> input) implements ContentBlock {}

    /** Tool execution result fed back to model. */
    record ToolResultBlock(String toolUseId, String name, String content, boolean isError) implements ContentBlock {
        public static ToolResultBlock success(String id, String name, String content) {
            return new ToolResultBlock(id, name, content, false);
        }
        public static ToolResultBlock error(String id, String name, String error) {
            return new ToolResultBlock(id, name, error, true);
        }
    }

    /** Extended thinking / chain-of-thought content. */
    record ThinkingBlock(String thought, String signature) implements ContentBlock {
        public ThinkingBlock(String thought) { this(thought, null); }
    }

    // Factory methods
    static TextBlock text(String s) { return new TextBlock(s); }
    static ToolUseBlock toolUse(String id, String name, Map<String, Object> input) { return new ToolUseBlock(id, name, input); }
    static ToolResultBlock toolResult(String id, String name, String content, boolean error) { return new ToolResultBlock(id, name, content, error); }
    static ThinkingBlock thinking(String thought) { return new ThinkingBlock(thought); }
}
