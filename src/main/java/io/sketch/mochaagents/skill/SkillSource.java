package io.sketch.mochaagents.skill;

/**
 * 技能来源枚举 — 对齐 claude-code 的 LoadedFrom / source.
 * @author lanxia39@163.com
 */
public enum SkillSource {
    /** 编译进 JAR 的内置技能. */
    BUNDLED,
    /** 从文件系统 .mocha/skills/ 加载的技能. */
    FILE_SYSTEM,
    /** 从插件注册的技能（预留）. */
    PLUGIN
}
