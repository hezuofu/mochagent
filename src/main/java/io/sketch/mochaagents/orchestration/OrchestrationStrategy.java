package io.sketch.mochaagents.orchestration;

import io.sketch.mochaagents.agent.Agent;

/**
 * 编排策略 — 定义多 Agent 如何协作完成任务.
 */
@FunctionalInterface
/** @author lanxia39@163.com */
public interface OrchestrationStrategy {

    /** 执行编排策略，返回结果 */
    <I, O> O execute(AgentTeam team, I input);

    /** 顺序执行策略 */
    static OrchestrationStrategy sequential() {
        return new OrchestrationStrategy() {
            @Override
            @SuppressWarnings("unchecked")
            public <I, O> O execute(AgentTeam team, I input) {
                Object result = input;
                for (Agent<?, ?> agent : team.getAgents()) {
                    result = ((Agent<Object, Object>) (Object) agent).execute(result);
                }
                return (O) result;
            }
        };
    }

    /** 并行执行策略 */
    static OrchestrationStrategy parallel() {
        return new OrchestrationStrategy() {
            @Override
            @SuppressWarnings("unchecked")
            public <I, O> O execute(AgentTeam team, I input) {
                java.util.List<Object> results = team.getAgents().stream()
                        .map(a -> ((Agent<I, Object>) (Object) a).execute(input))
                        .toList();
                return (O) results;
            }
        };
    }
}
