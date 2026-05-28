// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.orchestration;

import io.sketch.mochaagents.agent.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Hierarchical strategy — Leader decomposes via Planner, Workers execute topologically.
 *
 * <p>Delegates single-task execution to {@link TaskExecutor} so graph traversal and
 * task execution are separate concerns.
 *
 * @author lanxia39@163.com
 */
public class HierarchicalStrategy implements OrchestrationStrategy {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalStrategy.class);

    private final TaskExecutor taskExecutor;
    private final OrchestrationStrategy delegate;

    public HierarchicalStrategy(TaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
        this.delegate = null;
    }

    HierarchicalStrategy(TaskExecutor taskExecutor, OrchestrationStrategy delegate) {
        this.taskExecutor = taskExecutor;
        this.delegate = delegate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O execute(AgentTeam team, I input) {
        // If delegate is set, use it for graph traversal
        if (delegate != null) return delegate.execute(team, input);

        // Default: simple sequential execution
        return (O) "HierarchicalStrategy requires a TaskGraph or delegate for orchestration";
    }

    /** Execute a TaskGraph against the team. */
    public Map<String, Object> executeGraph(TaskGraph graph, AgentTeam team, OrchestrationContext ctx) {
        List<Agent<?, ?>> workers = new ArrayList<>(team.getByRole(RoleType.WORKER));
        if (workers.isEmpty()) workers = new ArrayList<>(team.getAgents());

        List<List<TaskGraph.Node>> levels = graph.topologicalLevels();
        for (var level : levels) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (var node : level) {
                Agent<?, ?> worker = findWorker(node.requiredCapability, workers);
                futures.add(CompletableFuture.runAsync(() -> {
                    Object result = taskExecutor.execute(node.description, worker);
                    ctx.recordResult(node.id, result);
                }));
            }
            try { CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(ctx.policy().timeoutMs() * Math.max(1, level.size()), java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (Exception e) { log.error("Level execution failed: {}", e.getMessage()); }
        }
        return ctx.allResults();
    }

    private Agent<?, ?> findWorker(String capability, List<Agent<?, ?>> workers) {
        if (capability == null || capability.isEmpty() || workers.isEmpty()) {
            return workers.isEmpty() ? null : workers.get(0);
        }
        return workers.stream()
                .filter(w -> w.metadata().name().toLowerCase().contains(capability.toLowerCase()))
                .findFirst()
                .orElse(workers.get(0));
    }
}
