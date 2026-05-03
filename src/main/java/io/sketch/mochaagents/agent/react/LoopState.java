package io.sketch.mochaagents.agent.react;

/**
 * Agentic Loop 循环状态枚举.
 * @author lanxia39@163.com
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
