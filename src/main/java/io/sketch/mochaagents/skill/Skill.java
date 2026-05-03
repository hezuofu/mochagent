package io.sketch.mochaagents.skill;

import java.util.Collections;
import java.util.List;

/**
 * 技能接口 — 核心技能抽象，对齐 claude-code 的 BundledSkillDefinition / Command.
 *
 * <p>技能是一种特殊的"元工具"：模型调用 SkillTool 时，技能返回 prompt 内容注入对话上下文，
 * 而非直接执行副作用。典型用途包括代码审查、提交规范、调试流程等专家知识模板。
 *
 * <p>设计要点:
 * <ul>
 *   <li>技能不是 Tool（不修改外部状态），而是 prompt 模板</li>
 *   <li>getPromptForCommand 返回 ContentBlock 列表，由 SkillTool 注入 LLM 上下文</li>
 *   <li>default 方法提供安全默认值，实现类按需覆盖</li>
 *   <li>allowedTools 可限制子任务工具白名单</li>
 * </ul>
 * @author lanxia39@163.com
 */
public interface Skill {

    // ==================== 核心标识 ====================

    /** 技能唯一名称，供 SkillTool 查找. */
    String name();

    /** 技能功能描述，供 LLM 理解何时调用. */
    String description();

    /** 技能别称列表. */
    default List<String> aliases() {
        return Collections.emptyList();
    }

    // ==================== 元数据 ====================

    /** 何时使用此技能的提示（写入 tool description 或系统 prompt）. */
    default String whenToUse() {
        return "";
    }

    /** 参数提示（如 "commit message"）. */
    default String argumentHint() {
        return "";
    }

    /** 技能版本. */
    default String version() {
        return "";
    }

    /** 技能来源. */
    SkillSource source();

    /** 技能是否启用. */
    default boolean isEnabled() {
        return true;
    }

    /** 是否允许用户直接调用（/skillName）. */
    default boolean isUserInvocable() {
        return true;
    }

    // ==================== 执行控制 ====================

    /** 执行上下文：INLINE（默认）或 FORK（预留）. */
    default SkillContext context() {
        return SkillContext.INLINE;
    }

    /** 允许的工具白名单（空列表 = 不限制）. */
    default List<String> allowedTools() {
        return Collections.emptyList();
    }

    /** 模型覆盖（null = 使用当前会话模型）. */
    default String model() {
        return null;
    }

    /** 是否禁用模型自动调用. */
    default boolean disableModelInvocation() {
        return false;
    }

    // ==================== Prompt 生成 ====================

    /**
     * 生成技能 prompt 内容.
     * 技能被调用时，返回的 ContentBlock 列表会作为 user message 注入 LLM 上下文。
     *
     * @param args 用户传递的参数（可能为空字符串）
     * @return prompt 内容块列表
     */
    List<ContentBlock> getPromptForCommand(String args);
}
