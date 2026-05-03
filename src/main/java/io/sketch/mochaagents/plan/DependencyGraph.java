package io.sketch.mochaagents.plan;

import java.util.*;

/**
 * 依赖图 — 管理步骤之间的依赖关系，支持拓扑排序和循环检测.
 * @author lanxia39@163.com
 */
public class DependencyGraph {

    private final Map<String, List<String>> adjacency = new HashMap<>();

    public void addDependency(String stepId, String dependsOn) {
        adjacency.computeIfAbsent(stepId, k -> new ArrayList<>()).add(dependsOn);
    }

    public List<String> getDependencies(String stepId) {
        return adjacency.getOrDefault(stepId, List.of());
    }

    public boolean hasCycle() {
        Set<String> visited = new HashSet<>();
        Set<String> stack = new HashSet<>();
        for (String node : adjacency.keySet()) {
            if (hasCycleDfs(node, visited, stack)) return true;
        }
        return false;
    }

    private boolean hasCycleDfs(String node, Set<String> visited, Set<String> stack) {
        if (stack.contains(node)) return true;
        if (visited.contains(node)) return false;
        visited.add(node);
        stack.add(node);
        for (String dep : adjacency.getOrDefault(node, List.of())) {
            if (hasCycleDfs(dep, visited, stack)) return true;
        }
        stack.remove(node);
        return false;
    }

    /**
     * 拓扑排序.
     */
    public List<String> topologicalSort() {
        Map<String, Integer> inDegree = new HashMap<>();
        for (String node : adjacency.keySet()) {
            inDegree.putIfAbsent(node, 0);
            for (String dep : adjacency.get(node)) {
                inDegree.merge(dep, 1, Integer::sum);
                inDegree.putIfAbsent(node, 0);
            }
        }
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String n = queue.poll();
            order.add(n);
            for (String dep : adjacency.getOrDefault(n, List.of())) {
                inDegree.merge(dep, -1, Integer::sum);
                if (inDegree.get(dep) == 0) queue.add(dep);
            }
        }
        return order;
    }
}
