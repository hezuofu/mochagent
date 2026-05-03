package io.sketch.mochaagents.skill;

/**
 * Skill prompt 输出块 — 对齐 claude-code 的 ContentBlockParam.
 * 技能执行后返回 prompt 内容，由 SkillTool 注入 LLM 上下文。
 * @author lanxia39@163.com
 */
public record ContentBlock(String type, String text) {

    public ContentBlock {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
    }

    /** 便捷构造：纯文本块. */
    public ContentBlock(String text) {
        this("text", text);
    }

    public static ContentBlock text(String text) {
        return new ContentBlock("text", text);
    }
}
