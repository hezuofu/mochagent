package io.sketch.mochaagents.interaction;

/**
 * 交互模式 — 定义 Agent 与用户之间的交互层级.
 */
public enum InteractionMode {

    /** 自主模式 — Agent 全自动运行，无需人工干预 */
    AUTONOMOUS,

    /** 协作模式 — Agent 与用户协作，关键决策需确认 */
    COLLABORATIVE,

    /** 监督模式 — 所有操作需人工审核批准 */
    SUPERVISED,

    /** 交互模式 — 只读询问，不执行写操作 */
    INTERACTIVE
}
