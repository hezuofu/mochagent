package io.sketch.mochaagents.agent.react;

import io.sketch.mochaagents.agent.Agent;
import java.util.function.Predicate;

/**
 * Agentic loop — the Think-Act-Observe main loop.
 * @param <I> input type
 * @param <O> output type
 * @author lanxia39@163.com
 */
public interface AgenticLoop<I, O> {
    O run(Agent<I, O> agent, I input, Predicate<StepResult> condition);
    StepResult step(Agent<I, O> agent, I input, int stepNum);
}
