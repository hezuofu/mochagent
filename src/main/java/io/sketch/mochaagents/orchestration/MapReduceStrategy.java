// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.orchestration;

import io.sketch.mochaagents.agent.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * MapReduce strategy — Map tasks to workers, Reduce results through a reducer agent.
 *
 * <pre>{@code
 * var strategy = new MapReduceStrategy(reducerAgent)
 *     .withMapper(task -> extractSubTask(task));
 * }</pre>
 *
 * @author lanxia39@163.com
 */
public class MapReduceStrategy implements OrchestrationStrategy {

    private static final Logger log = LoggerFactory.getLogger(MapReduceStrategy.class);

    private final Agent<String, String> reducer;
    private final ExecutionPolicy policy;
    private Function<String, List<String>> mapper = input -> List.of(input);

    public MapReduceStrategy(Agent<String, String> reducer, ExecutionPolicy policy) {
        this.reducer = reducer; this.policy = policy;
    }

    public MapReduceStrategy(Agent<String, String> reducer) {
        this(reducer, ExecutionPolicy.DEFAULT);
    }

    public MapReduceStrategy withMapper(Function<String, List<String>> mapper) {
        this.mapper = mapper; return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O execute(AgentTeam team, I input) {
        List<String> subTasks = mapper.apply(String.valueOf(input));
        List<Agent<?, ?>> workers = new ArrayList<>(team.getByRole(RoleType.WORKER));
        if (workers.isEmpty()) workers = new ArrayList<>(team.getAgents());

        // Map phase: distribute sub-tasks to workers in parallel
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < subTasks.size(); i++) {
            final String task = subTasks.get(i);
            final Agent<String, String> worker = (Agent<String, String>) (Object) workers.get(i % workers.size());
            futures.add(CompletableFuture.supplyAsync(() -> {
                for (int attempt = 0; attempt <= policy.maxRetries(); attempt++) {
                    try { return worker.execute(task); }
                    catch (Exception e) {
                        if (attempt >= policy.maxRetries()) {
                            log.error("Map task failed after {} retries: {}", attempt + 1, e.getMessage());
                            return "[FAILED: " + e.getMessage() + "]";
                        }
                    }
                }
                return "[FAILED]";
            }));
        }

        // Collect map results
        List<String> mapResults = new ArrayList<>();
        for (var f : futures) {
            try { mapResults.add(f.get(policy.timeoutMs(), TimeUnit.MILLISECONDS)); }
            catch (Exception e) { mapResults.add("[TIMEOUT: " + e.getMessage() + "]"); }
        }

        // Reduce phase: reducer agent aggregates
        String reduceInput = "Aggregate these sub-task results into a final answer:\n";
        for (int i = 0; i < mapResults.size(); i++) {
            reduceInput += "Task " + (i + 1) + " [" + subTasks.get(i) + "]: " + mapResults.get(i) + "\n";
        }
        return (O) reducer.execute(reduceInput);
    }
}
