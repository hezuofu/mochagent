package io.sketch.mochaagents.core;

/**
 * Agent 生命周期状态枚举.
 */
public enum AgentState {
    IDLE,
    INITIALIZING,
    RUNNING,
    WAITING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    DESTROYED
}
