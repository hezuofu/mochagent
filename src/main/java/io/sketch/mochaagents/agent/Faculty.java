package io.sketch.mochaagents.agent;

import io.sketch.mochaagents.evaluation.Evaluator;
import io.sketch.mochaagents.perception.PerceptionResult;
import io.sketch.mochaagents.perception.Perceptor;
import io.sketch.mochaagents.plan.Plan;
import io.sketch.mochaagents.plan.Planner;
import io.sketch.mochaagents.plan.PlanningRequest;
import io.sketch.mochaagents.reasoning.Reasoner;
import io.sketch.mochaagents.reasoning.ReasoningChain;

/**
 * Faculty — a composable cognitive capability that wraps an Agent.
 *
 * <p>Each Faculty is a single-responsibility decorator: perception observes
 * input, reasoning analyzes it, planning builds a blueprint, evaluation
 * assesses output. Faculties compose via {@link #then(Faculty)}.
 *
 * <pre>{@code
 * var agent = MochaAgent.builder().llm(llm).tools(tools).build()
 *     .with(Faculty.Perception.of(perceptor))
 *     .with(Faculty.Reasoning.of(reasoner))
 *     .with(Faculty.Planning.of(planner));
 * }</pre>
 */
@FunctionalInterface
public interface Faculty<I, O> {

    Agent<I, O> apply(Agent<I, O> agent);

    default Faculty<I, O> then(Faculty<I, O> next) {
        return agent -> next.apply(apply(agent));
    }

    // ── Built-in faculties ──

    final class Perception {
        private Perception() {}
        public static <I, O> Faculty<I, O> of(Perceptor<I, O> p) {
            return agent -> new AgentWrapper<>(agent) {
                @Override public O execute(I input, AgentContext ctx) {
                    PerceptionResult<O> r = p.perceive(input);
                    return inner.execute(input, ctx.withPerception(r));
                }
            };
        }
    }

    final class Reasoning {
        private Reasoning() {}
        public static <I, O> Faculty<I, O> of(Reasoner r) {
            return agent -> new AgentWrapper<>(agent) {
                @Override public O execute(I input, AgentContext ctx) {
                    ReasoningChain chain = r.reason(
                            input != null ? input.toString() : "");
                    return inner.execute(input, ctx.withReasoning(chain));
                }
            };
        }
    }

    final class Planning {
        private Planning() {}
        @SuppressWarnings("unchecked")
        public static <I, O> Faculty<I, O> of(Planner<O> planner) {
            return agent -> new AgentWrapper<>(agent) {
                @Override public O execute(I input, AgentContext ctx) {
                    Plan<O> plan = planner.generatePlan(
                            PlanningRequest.<O>builder().goal((O) input).build());
                    return inner.execute(input, ctx.withPlan(plan));
                }
            };
        }
    }

    final class Evaluation {
        private Evaluation() {}
        public static <I, O> Faculty<I, O> of(Evaluator e) {
            return agent -> new AgentWrapper<>(agent) {
                @Override public O execute(I input, AgentContext ctx) {
                    O result = inner.execute(input, ctx);
                    e.evaluate(input != null ? input.toString() : "",
                            result != null ? result.toString() : "", null);
                    return result;
                }
            };
        }
    }

    static <I, O> Faculty<I, O> identity() { return agent -> agent; }
}
