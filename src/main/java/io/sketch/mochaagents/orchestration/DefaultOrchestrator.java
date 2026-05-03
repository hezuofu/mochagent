package io.sketch.mochaagents.orchestration;

import io.sketch.mochaagents.agent.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 默认编排器 — Orchestrator 接口的通用实现.
 *
 * <p>维护 AgentTeam 注册表，委托 OrchestrationStrategy 执行编排逻辑.
 * 线程安全，支持运行时注册/注销 Agent.
 * @author lanxia39@163.com
 */
public class DefaultOrchestrator implements Orchestrator {

    private static final Logger log = LoggerFactory.getLogger(DefaultOrchestrator.class);

    private final ConcurrentMap<String, Agent<?, ?>> agents = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Role> roles = new ConcurrentHashMap<>();
    private final AgentTeam team;
    private volatile OrchestrationStrategy activeStrategy;

    public DefaultOrchestrator() {
        this.team = new AgentTeam("default");
    }

    @Override
    public void register(Agent<?, ?> agent, Role role) {
        String id = agent.metadata().name();
        agents.put(id, agent);
        roles.put(id, role);
        team.addMember(agent, role);
        log.info("Agent registered: {} as {}", id, role.type());
    }

    @Override
    public void unregister(String agentId) {
        Agent<?, ?> removed = agents.remove(agentId);
        roles.remove(agentId);
        if (removed != null) {
            team.removeMember(agentId);
            log.info("Agent unregistered: {}", agentId);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O orchestrate(I input, OrchestrationStrategy strategy) {
        if (strategy == null) throw new IllegalArgumentException("Strategy is required");
        this.activeStrategy = strategy;
        log.info("Orchestrating with strategy: {}", strategy.getClass().getSimpleName());

        try {
            return (O) strategy.execute(team, input);
        } catch (Exception e) {
            log.error("Orchestration failed: {}", e.getMessage(), e);
            throw new RuntimeException("Orchestration failed: " + e.getMessage(), e);
        }
    }

    @Override
    public <I, O> CompletableFuture<O> orchestrateAsync(I input, OrchestrationStrategy strategy) {
        return CompletableFuture.supplyAsync(() -> orchestrate(input, strategy));
    }

    @Override
    public AgentTeam getTeam() { return team; }

    @Override
    public OrchestrationStrategy getStrategy() { return activeStrategy; }

    @Override
    public void shutdown() {
        agents.clear();
        roles.clear();
        new ArrayList<>(team.getAgents()).forEach(a -> team.removeMember(a.metadata().name()));
        log.info("Orchestrator shut down");
    }

    /** 已注册 Agent 数量 */
    public int agentCount() { return agents.size(); }
}
