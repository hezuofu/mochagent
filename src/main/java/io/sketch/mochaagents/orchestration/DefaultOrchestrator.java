// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.orchestration;

import io.sketch.mochaagents.agent.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default orchestrator — general-purpose Orchestrator implementation.
 *
 * <p>Maintains an AgentTeam registry, delegates to OrchestrationStrategy for
 * execution logic, and uses a configurable {@link TaskRunner} (default:
 * {@link DirectRunner}) for individual agent calls — enabling retry, timeout,
 * and fallback through the {@link RetryingRunner} decorator.
 *
 * <p>Thread-safe. Supports runtime agent registration/unregistration.
 *
 * @author lanxia39@163.com
 */
public class DefaultOrchestrator implements Orchestrator {

    private static final Logger log = LoggerFactory.getLogger(DefaultOrchestrator.class);

    private final ConcurrentMap<String, Agent<?, ?>> agents = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Role> roles = new ConcurrentHashMap<>();
    private final AgentTeam team;
    private volatile OrchestrationStrategy activeStrategy;
    private volatile TaskRunner taskRunner = new DirectRunner();
    private volatile ExecutionPolicy defaultPolicy = ExecutionPolicy.DEFAULT;

    public DefaultOrchestrator() {
        this.team = new AgentTeam("default");
    }

    // ── Configuration ──

    /** Set the TaskRunner used for all individual agent calls. */
    public DefaultOrchestrator withRunner(TaskRunner runner) {
        this.taskRunner = runner;
        return this;
    }

    /** Configure retry policy — wraps the current runner in a RetryingRunner. */
    public DefaultOrchestrator withPolicy(ExecutionPolicy policy) {
        this.defaultPolicy = policy;
        this.taskRunner = new RetryingRunner(new DirectRunner(), policy);
        return this;
    }

    /** @return the current TaskRunner in use. */
    public TaskRunner taskRunner() { return taskRunner; }

    // ── Agent management ──

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

    // ── Orchestration ──

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O orchestrate(I input, OrchestrationStrategy strategy) {
        if (strategy == null) throw new IllegalArgumentException("Strategy is required");
        this.activeStrategy = strategy;
        log.info("Orchestrating with strategy: {}", strategy.getClass().getSimpleName());

        try {
            return strategy.execute(team, input, taskRunner);
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

    /** Count of registered agents. */
    public int agentCount() { return agents.size(); }
}
