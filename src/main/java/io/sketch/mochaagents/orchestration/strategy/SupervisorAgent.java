package io.sketch.mochaagents.orchestration.strategy;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.orchestration.*;
import java.util.List;

/**
 * 监督 Agent 策略 — 一个监督 Agent 分配任务给工作 Agent，审查结果.
 */
public class SupervisorAgent implements OrchestrationStrategy {

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O execute(AgentTeam team, I input) {
        List<Agent<?, ?>> workers = team.getByRole(RoleType.WORKER);
        List<Agent<?, ?>> leaders = team.getLeaders();

        // 领导者分解任务并分配
        final Object task;
        if (!leaders.isEmpty()) {
            task = ((Agent<I, Object>) (Object) leaders.get(0)).execute(input);
        } else {
            task = input;
        }

        // 工作者并行执行
        List<Object> results = workers.stream()
                .map(w -> ((Agent<Object, Object>) (Object) w).execute(task))
                .toList();

        // 领导者汇总结果
        if (!leaders.isEmpty()) {
            return ((Agent<List<Object>, O>) (Object) leaders.get(0)).execute(results);
        }

        return (O) results;
    }
}
