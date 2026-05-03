package io.sketch.mochaagents.orchestration.strategy;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.orchestration.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 监督 Agent 策略 — 一个监督 Agent 分配任务给工作 Agent，审查结果.
 * @author lanxia39@163.com
 */
public class SupervisorStrategy implements OrchestrationStrategy {

    private static final Logger log = LoggerFactory.getLogger(SupervisorStrategy.class);

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O execute(AgentTeam team, I input) {
        log.info("SupervisorStrategy executing for team '{}'", team.name());
        List<Agent<?, ?>> workers = team.getByRole(RoleType.WORKER);
        List<Agent<?, ?>> leaders = team.getLeaders();
        log.debug("SupervisorStrategy: {} leaders, {} workers", leaders.size(), workers.size());

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
            O result = ((Agent<List<Object>, O>) (Object) leaders.get(0)).execute(results);
            log.info("SupervisorStrategy completed for team '{}'", team.name());
            return result;
        }

        log.info("SupervisorStrategy completed for team '{}' (no leaders)", team.name());
        return (O) results;
    }
}
