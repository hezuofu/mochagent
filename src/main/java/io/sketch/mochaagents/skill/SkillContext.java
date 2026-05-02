package io.sketch.mochaagents.skill;

/**
 * 技能执行上下文模式 — 对齐 claude-code 的 context: 'inline' | 'fork'.
 * <ul>
 *   <li>INLINE — 技能 prompt 直接注入当前对话（默认）</li>
 *   <li>FORK — 在隔离子 Agent 中执行（预留）</li>
 * </ul>
 */
public enum SkillContext {
    INLINE, FORK
}
