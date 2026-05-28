// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.orchestration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Execution state for an orchestration run — results, completed tasks, progress.
 *
 * <p>Thread-safe via ConcurrentHashMap for parallel strategy execution.
 *
 * @author lanxia39@163.com
 */
public class OrchestrationContext {

    private final Map<String, Object> results = new ConcurrentHashMap<>();
    private final Set<String> completed = ConcurrentHashMap.newKeySet();
    private final ExecutionPolicy policy;
    private final AgentTeam team;

    public OrchestrationContext(AgentTeam team, ExecutionPolicy policy) {
        this.team = team; this.policy = policy;
    }

    public void recordResult(String taskId, Object result) {
        results.put(taskId, result);
        completed.add(taskId);
    }

    public Optional<Object> result(String taskId) { return Optional.ofNullable(results.get(taskId)); }
    public boolean isCompleted(String taskId) { return completed.contains(taskId); }
    public int completedCount() { return completed.size(); }
    public Map<String, Object> allResults() { return Map.copyOf(results); }

    public AgentTeam team() { return team; }
    public ExecutionPolicy policy() { return policy; }
}
