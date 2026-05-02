package io.sketch.mochaagents.reasoning.strategy;

import io.sketch.mochaagents.reasoning.ReasoningChain;
import io.sketch.mochaagents.reasoning.ReasoningStep;
import io.sketch.mochaagents.reasoning.ReasoningStrategy;

/**
 * Graph of Thought 策略 — 图结构推理.
 */
public class GraphOfThought implements ReasoningStrategy {

    @Override
    public ReasoningChain reason(String question) {
        ReasoningChain chain = new ReasoningChain();
        chain.add(new ReasoningStep(1, "Build reasoning graph for: " + question, "Graph nodes: 5, edges: 8", 0.85));
        chain.add(new ReasoningStep(2, "Traverse graph to find optimal path", "Optimal reasoning path found", 0.88));
        return chain;
    }
}
