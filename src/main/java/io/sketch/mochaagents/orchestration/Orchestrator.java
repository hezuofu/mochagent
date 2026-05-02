package io.sketch.mochaagents.orchestration;

import io.sketch.mochaagents.core.Agent;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 编排器 — 协调多个 Agent 协同工作，管理任务分配与结果聚合.
 */
public interface Orchestrator {

    /** 注册 Agent */
    void register(Agent<?, ?> agent, Role role);

    /** 注销 Agent */
    void unregister(String agentId);

    /** 启动编排 */
    <I, O> O orchestrate(I input, OrchestrationStrategy strategy);

    /** 异步编排 */
    <I, O> CompletableFuture<O> orchestrateAsync(I input, OrchestrationStrategy strategy);

    /** 获取 Agent 团队 */
    AgentTeam getTeam();

    /** 获取当前编排策略 */
    OrchestrationStrategy getStrategy();

    /** 停止编排 */
    void shutdown();
}
