package io.sketch.mochaagents.agent;

import io.sketch.mochaagents.agent.loop.StepResult;
import java.util.function.Predicate;

/**
 * Agentic loop — the Think-Act-Observe main loop.
 * @param <I> input type
 * @param <O> output type
 * @author lanxia39@163.com
 */
public interface AgentLoop<I, O> {
    O run(Agent<I, O> agent, I input, Predicate<StepResult> condition);
    StepResult step(Agent<I, O> agent, I input, int stepNum);
}
