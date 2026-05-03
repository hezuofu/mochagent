package io.sketch.mochaagents.reasoning.strategy;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.reasoning.ReasoningChain;
import io.sketch.mochaagents.reasoning.ReasoningStep;
import io.sketch.mochaagents.reasoning.ReasoningStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Graph of Thought — 构建推理节点有向图，拓扑排序后选出最可信路径.
 * @author lanxia39@163.com
 */
public class GraphOfThought implements ReasoningStrategy {

    private static final Logger log = LoggerFactory.getLogger(GraphOfThought.class);
    private final LLM llm;

    public GraphOfThought(LLM llm) { this.llm = llm; }

    @Override
    public ReasoningChain reason(String question) {
        ReasoningChain chain = new ReasoningChain();

        String prompt = """
                Decompose into 3-5 atomic thought nodes.
                ID: <N1, N2, ...>
                Thought: <the idea>
                DependsOn: <prerequisite IDs or "none">
                Confidence: <0.0-1.0>

                Question: %s""".formatted(question);

        String response = llm.complete(LLMRequest.builder()
                .addMessage("user", prompt).maxTokens(2048).temperature(0.5).build()).content();

        List<ThoughtNode> nodes = parseNodes(response);
        Map<String, ThoughtNode> nodeMap = new LinkedHashMap<>();
        for (ThoughtNode n : nodes) nodeMap.put(n.id, n);

        chain.add(new ReasoningStep(0, "Graph: " + nodes.size() + " nodes",
                "Edges: " + nodes.stream().mapToInt(n -> n.deps.size()).sum(), 1.0));

        // Topological sort
        Set<String> visited = new LinkedHashSet<>();
        List<ThoughtNode> ordered = new ArrayList<>();
        for (ThoughtNode n : nodes) traverse(n, nodeMap, visited, ordered, new HashSet<>());

        for (int i = 0; i < ordered.size(); i++) {
            ThoughtNode n = ordered.get(i);
            chain.add(new ReasoningStep(i + 1, n.thought,
                    String.format("%.2f", n.confidence)
                    + (n.deps.isEmpty() ? "" : " ← " + n.deps), n.confidence));
        }

        log.debug("GraphOfThought: {} nodes processed", nodes.size());
        return chain;
    }

    private List<ThoughtNode> parseNodes(String text) {
        List<ThoughtNode> nodes = new ArrayList<>();
        for (String block : text.split("(?=ID\\s*[:：])")) {
            if (block.isBlank()) continue;
            String id = extractField(block, "ID\\s*[:：]\\s*", "N" + (nodes.size() + 1));
            String thought = extractField(block, "Thought\\s*[:：]\\s*", block);
            String deps = extractField(block, "DependsOn\\s*[:：]\\s*", "");
            double conf = extractConfidence(block);
            Set<String> depSet = new LinkedHashSet<>();
            if (!deps.isEmpty() && !"none".equalsIgnoreCase(deps))
                for (String d : deps.split("[,\\s]+")) if (!d.isBlank()) depSet.add(d.trim());
            nodes.add(new ThoughtNode(id.trim(), thought.trim(), depSet, conf));
        }
        if (nodes.isEmpty()) nodes.add(new ThoughtNode("N1", text.trim(), Set.of(), 0.8));
        return nodes;
    }

    private void traverse(ThoughtNode node, Map<String, ThoughtNode> all,
                          Set<String> visited, List<ThoughtNode> result, Set<String> path) {
        if (visited.contains(node.id) || path.contains(node.id)) return;
        path.add(node.id);
        for (String depId : node.deps) {
            ThoughtNode dep = all.get(depId);
            if (dep != null) traverse(dep, all, visited, result, path);
        }
        visited.add(node.id);
        result.add(node);
        path.remove(node.id);
    }

    private String extractField(String text, String regex, String fallback) {
        var m = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) { String[] lines = text.substring(m.end()).trim().split("\n", 2);
            return lines.length > 0 ? lines[0].trim() : fallback; }
        return fallback;
    }

    private double extractConfidence(String text) {
        var m = java.util.regex.Pattern.compile("Confidence\\s*[:：]\\s*([\\d.]+)").matcher(text);
        if (m.find()) {
            try { return Math.min(1.0, Math.max(0.0, Double.parseDouble(m.group(1)))); }
            catch (NumberFormatException e) {}
        }
        return 0.7;
    }

    private record ThoughtNode(String id, String thought, Set<String> deps, double confidence) {}
}
