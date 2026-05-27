// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.orchestration;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.plan.Planner;
import io.sketch.mochaagents.plan.PlanningRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Hierarchical strategy — Leader decomposes, Workers execute, Leader aggregates.
 *
 * <p>Flow:
 * <ol>
 *   <li>Leader receives input, calls {@link Planner} to decompose into {@link TaskGraph}</li>
 *   <li>Workers execute tasks topologically (respecting DAG dependencies)</li>
 *   <li>Within each level, tasks run in parallel on matching workers</li>
 *   <li>Leader aggregates results and produces final answer</li>
 * </ol>
 *
 * @author lanxia39@163.com
 */
public class HierarchicalStrategy implements OrchestrationStrategy {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalStrategy.class);

    private final Planner<String> planner;
    private final ExecutionPolicy policy;

    public HierarchicalStrategy(Planner<String> planner, ExecutionPolicy policy) {
        this.planner = planner;
        this.policy = policy;
    }

    public HierarchicalStrategy(Planner<String> planner) {
        this(planner, ExecutionPolicy.DEFAULT);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O execute(AgentTeam team, I input) {
        // 1. Leader decomposes
        List<Agent<?, ?>> leaders = team.getLeaders();
        if (leaders.isEmpty()) throw new IllegalStateException("No leader agent in team");
        Agent<I, String> leader = (Agent<I, String>) (Object) leaders.get(0);

        PlanningRequest req = PlanningRequest.<String>builder()
                .goal(String.valueOf(input)).build();
        var plan = (io.sketch.mochaagents.plan.Plan<String>) planner.generatePlan(req);

        // Build TaskGraph from plan
        TaskGraph graph = new TaskGraph();
        String prevId = null;
        for (var step : plan.getSteps()) {
            var node = graph.addTask(step.stepId(), step.description(), step.agentId());
            if (prevId != null) node.dependsOn(prevId);
            for (String dep : step.dependencies()) node.dependsOn(dep);
            prevId = step.stepId();
        }

        // 2. Execute graph level by level
        List<Agent<?, ?>> workers = team.getByRole(RoleType.WORKER);
        Map<String, Object> results = executeGraph(graph, workers);

        // 3. Leader aggregates
        String summary = "Completed " + results.size() + " tasks:\n";
        for (var e : results.entrySet()) {
            summary += "  " + e.getKey() + ": " + e.getValue() + "\n";
        }
        return (O) leader.execute((I) summary);
    }

    private Map<String, Object> executeGraph(TaskGraph graph, List<Agent<?, ?>> workers) {
        Map<String, Object> results = new LinkedHashMap<>();
        Set<String> completed = new HashSet<>();
        List<List<TaskGraph.Node>> levels = graph.topologicalLevels();

        for (var level : levels) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (var node : level) {
                futures.add(CompletableFuture.runAsync(() -> {
                    Agent<?, ?> worker = findWorker(node.requiredCapability, workers);
                    if (worker == null) {
                        log.warn("No worker for task {} (capability: {})", node.id, node.requiredCapability);
                        return;
                    }
                    for (int attempt = 0; attempt <= policy.maxRetries(); attempt++) {
                        try {
                            @SuppressWarnings("unchecked")
                            Object result = ((Agent<String, Object>) (Object) worker).execute(node.description);
                            synchronized (results) {
                                node.setResult(result);
                                results.put(node.id, result);
                                completed.add(node.id);
                            }
                            return;
                        } catch (Exception e) {
                            if (attempt >= policy.maxRetries()) throw e;
                            log.warn("Task {} attempt {}/{} failed: {}", node.id, attempt + 1, policy.maxRetries() + 1, e.getMessage());
                        }
                    }
                }));
            }
            try { CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(policy.timeoutMs() * level.size(), TimeUnit.MILLISECONDS); }
            catch (Exception e) { log.error("Level execution failed: {}", e.getMessage()); }
        }
        return results;
    }

    private Agent<?, ?> findWorker(String capability, List<Agent<?, ?>> workers) {
        if (capability == null || capability.isEmpty()) {
            return workers.isEmpty() ? null : workers.get(0);
        }
        // Match by agent name or metadata
        return workers.stream()
                .filter(w -> w.metadata().name().toLowerCase().contains(capability.toLowerCase()))
                .findFirst()
                .orElse(workers.isEmpty() ? null : workers.get(0));
    }
}
