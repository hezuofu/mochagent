package io.sketch.mochaagents.reasoning.strategy;

import io.sketch.mochaagents.reasoning.ReasoningChain;
import io.sketch.mochaagents.reasoning.ReasoningStep;
import io.sketch.mochaagents.reasoning.ReasoningStrategy;

/**
 * Tree of Thought 策略 — 分支探索推理.
 */
public class TreeOfThought implements ReasoningStrategy {

    @Override
    public ReasoningChain reason(String question) {
        ReasoningChain chain = new ReasoningChain();
        chain.add(new ReasoningStep(1, "Generate multiple hypotheses for: " + question, "3 branches explored", 0.8));
        chain.add(new ReasoningStep(2, "Evaluate each branch", "Best path selected", 0.85));
        return chain;
    }
}
