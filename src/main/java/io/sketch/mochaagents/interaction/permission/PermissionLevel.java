package io.sketch.mochaagents.interaction.permission;

/**
 * 权限级别 — 定义操作的风险等级.
 * @author lanxia39@163.com
 */
public enum PermissionLevel {

    /** 只读操作 — 无风险 */
    READ,

    /** 低风险操作 — 非破坏性写入 */
    LOW,

    /** 中等风险 — 可能影响系统状态 */
    MEDIUM,

    /** 高风险 — 影响代码/数据 */
    HIGH,

    /** 关键风险 — 系统级操作 */
    CRITICAL
}
