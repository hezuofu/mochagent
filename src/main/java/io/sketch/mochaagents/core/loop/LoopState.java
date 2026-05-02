package io.sketch.mochaagents.core.loop;

/**
 * Agentic Loop 循环状态枚举.
 */
public enum LoopState {
    INIT,
    OBSERVE,
    PLAN,
    ACT,
    REFLECT,
    COMPLETE,
    ERROR,
    INTERRUPTED
}
