package io.sketch.mochaagents.reasoning.strategy;

import io.sketch.mochaagents.reasoning.ReasoningChain;
import io.sketch.mochaagents.reasoning.ReasoningStep;
import io.sketch.mochaagents.reasoning.ReasoningStrategy;

/**
 * Program of Thought 策略 — 程序化推理.
 */
public class ProgramOfThought implements ReasoningStrategy {

    @Override
    public ReasoningChain reason(String question) {
        ReasoningChain chain = new ReasoningChain();
        chain.add(new ReasoningStep(1, "Transform into programmatic form: " + question, "Algorithm designed", 0.9));
        chain.add(new ReasoningStep(2, "Execute programmatic reasoning", "Computation complete", 0.95));
        return chain;
    }
}
