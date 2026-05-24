package io.sketch.mochaagents.agent;

import io.sketch.mochaagents.agent.loop.StepResult;
import java.util.function.Predicate;

/**
 * Agent execution loop — the Think-Act-Observe cycle.
 *
 * <p>Pluggable strategies (ReAct, Reflexion, ReWOO, TAO, OPAR) implement
 * this to control how an agent reasons, acts, and decides when to stop.
 *
 * @param <I> input type
 * @param <O> output type
 */
@FunctionalInterface
public interface AgentLoop<I, O> {

    O run(Agent<I, O> agent, I input, Predicate<StepResult> done);
}
