package io.sketch.mochaagents.agent;

/**
 * Agent 生命周期状态枚举.
 * @author lanxia39@163.com
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
