// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.orchestration;

import java.util.*;

/**
 * Directed Acyclic Graph of tasks — each task has dependencies that must complete first.
 *
 * @author lanxia39@163.com
 */
public class TaskGraph {

    private final Map<String, Node> nodes = new LinkedHashMap<>();

    public Node addTask(String id, String description, String requiredCapability) {
        Node n = new Node(id, description, requiredCapability);
        nodes.put(id, n);
        return n;
    }

    public Node task(String id) { return nodes.get(id); }
    public Collection<Node> tasks() { return Collections.unmodifiableCollection(nodes.values()); }
    public int size() { return nodes.size(); }

    /** Topological sort — ready tasks first (no pending deps). */
    public List<List<Node>> topologicalLevels() {
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<Node>> dependents = new LinkedHashMap<>();

        for (Node n : nodes.values()) {
            inDegree.putIfAbsent(n.id, 0);
            for (String dep : n.dependencies) {
                inDegree.merge(n.id, 1, Integer::sum);
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(n);
            }
        }

        List<List<Node>> levels = new ArrayList<>();
        Queue<Node> ready = new ArrayDeque<>();
        Set<String> processed = new HashSet<>();

        for (Node n : nodes.values()) {
            if (inDegree.getOrDefault(n.id, 0) == 0) ready.add(n);
        }

        while (!ready.isEmpty()) {
            List<Node> level = new ArrayList<>();
            int size = ready.size();
            for (int i = 0; i < size; i++) {
                Node n = ready.poll();
                level.add(n);
                processed.add(n.id);
                for (Node dep : dependents.getOrDefault(n.id, List.of())) {
                    int deg = inDegree.merge(dep.id, -1, Integer::sum);
                    if (deg == 0) ready.add(dep);
                }
            }
            if (!level.isEmpty()) levels.add(level);
        }

        if (processed.size() < nodes.size()) {
            throw new IllegalStateException("TaskGraph contains a cycle — cannot topologically sort");
        }
        return levels;
    }

    /** Returns true if a node is free to execute (all dependencies completed). */
    public boolean isReady(String taskId, Set<String> completed) {
        Node n = nodes.get(taskId);
        if (n == null) return false;
        return completed.containsAll(n.dependencies);
    }

    public static final class Node {
        public final String id;
        public final String description;
        public final String requiredCapability;
        final Set<String> dependencies = new LinkedHashSet<>();
        private Object result;

        Node(String id, String description, String requiredCapability) {
            this.id = id; this.description = description; this.requiredCapability = requiredCapability;
        }

        public Node dependsOn(String... taskIds) {
            dependencies.addAll(List.of(taskIds));
            return this;
        }

        public Set<String> dependencies() { return Collections.unmodifiableSet(dependencies); }
        public Optional<Object> result() { return Optional.ofNullable(result); }
        void setResult(Object r) { this.result = r; }
    }
}
