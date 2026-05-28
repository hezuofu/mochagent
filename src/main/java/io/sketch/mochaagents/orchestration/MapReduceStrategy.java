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
 * MapReduce strategy — Map tasks to workers, Reduce through aggregator.
 *
 * <p>Uses {@link TaskExecutor} for individual task execution.
 *
 * @author lanxia39@163.com
 */
public class MapReduceStrategy implements OrchestrationStrategy {

    private static final Logger log = LoggerFactory.getLogger(MapReduceStrategy.class);

    private final Agent<String, String> reducer;
    private final TaskExecutor taskExecutor;
    private Function<String, List<String>> mapper = input -> List.of(input);

    public MapReduceStrategy(Agent<String, String> reducer, TaskExecutor taskExecutor) {
        this.reducer = reducer; this.taskExecutor = taskExecutor;
    }

    public MapReduceStrategy(Agent<String, String> reducer) {
        this(reducer, TaskExecutor.direct());
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

        var ctx = new OrchestrationContext(team, ExecutionPolicy.DEFAULT);

        // Map phase — distribute to workers
        List<CompletableFuture<Map.Entry<Integer, String>>> futures = new ArrayList<>();
        for (int i = 0; i < subTasks.size(); i++) {
            final int idx = i;
            final String task = subTasks.get(i);
            final Agent<?, ?> worker = workers.get(i % workers.size());
            futures.add(CompletableFuture.supplyAsync(() -> {
                Object result = taskExecutor.execute(task, worker);
                return Map.entry(idx, result != null ? result.toString() : "[null]");
            }));
        }

        // Collect
        String[] mapResults = new String[subTasks.size()];
        for (var f : futures) {
            try {
                var entry = f.get(ctx.policy().timeoutMs(), TimeUnit.MILLISECONDS);
                mapResults[entry.getKey()] = entry.getValue();
            } catch (Exception e) {
                log.error("Map task failed: {}", e.getMessage());
            }
        }

        // Reduce phase
        StringBuilder sb = new StringBuilder("Aggregate these sub-task results:\n");
        for (int i = 0; i < subTasks.size(); i++) {
            sb.append("Task ").append(i + 1).append(" [").append(subTasks.get(i)).append("]: ")
              .append(mapResults[i] != null ? mapResults[i] : "[failed]").append("\n");
        }
        return (O) reducer.execute(sb.toString());
    }
}
