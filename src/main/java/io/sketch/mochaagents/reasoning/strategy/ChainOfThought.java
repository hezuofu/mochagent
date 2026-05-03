package io.sketch.mochaagents.reasoning.strategy;

import io.sketch.mochaagents.reasoning.ReasoningChain;
import io.sketch.mochaagents.reasoning.ReasoningStep;
import io.sketch.mochaagents.reasoning.ReasoningStrategy;

/**
 * Chain of Thought 策略 — 逐步推理.
 * @author lanxia39@163.com
 */
public class ChainOfThought implements ReasoningStrategy {

    @Override
    public ReasoningChain reason(String question) {
        ReasoningChain chain = new ReasoningChain();
        chain.add(new ReasoningStep(1, "Analyze the question: " + question, "Decomposed into sub-problems", 0.9));
        chain.add(new ReasoningStep(2, "Reason step by step", "Intermediate conclusion reached", 0.85));
        chain.add(new ReasoningStep(3, "Synthesize findings", "Final answer derived", 0.9));
        return chain;
    }
}
