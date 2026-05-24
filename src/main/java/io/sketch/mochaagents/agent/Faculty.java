// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent;

import io.sketch.mochaagents.evaluation.Evaluator;
import io.sketch.mochaagents.perception.PerceptionResult;
import io.sketch.mochaagents.perception.Perceptor;
import io.sketch.mochaagents.plan.Plan;
import io.sketch.mochaagents.plan.Planner;
import io.sketch.mochaagents.plan.PlanningRequest;
import io.sketch.mochaagents.reasoning.Reasoner;
import io.sketch.mochaagents.reasoning.ReasoningChain;

import java.util.function.BiFunction;

/**
 * Faculty — a composable cognitive capability that wraps an Agent.
 *
 * <pre>{@code
 * var agent = MochaAgent.builder("a", model).addTool(t)
 *     .with(Faculty.Perception.of(perceptor))
 *     .with(Faculty.Reasoning.of(reasoner))
 *     .build();
 * }</pre>
 */
@FunctionalInterface
public interface Faculty<I, O> {

    Agent<I, O> apply(Agent<I, O> agent);

    default Faculty<I, O> then(Faculty<I, O> next) {
        return agent -> next.apply(apply(agent));
    }

    // ── Helpers ──

    /** Pre-hook: enrich context before delegating to inner agent. */
    static <I, O> Faculty<I, O> pre(BiFunction<I, AgentContext, AgentContext> enrich) {
        return agent -> new AgentWrapper<>(agent) {
            @Override public O execute(I input, AgentContext ctx) {
                return inner.execute(input, enrich.apply(input, ctx));
            }
        };
    }

    // ── Built-in faculties ──

    final class Perception {
        private Perception() {}
        public static <I, O> Faculty<I, O> of(Perceptor<I, O> p) {
            return pre((input, ctx) -> {
                PerceptionResult<O> r = p.perceive(input);
                return ctx.withPerception(r);
            });
        }
    }

    final class Reasoning {
        private Reasoning() {}
        public static <I, O> Faculty<I, O> of(Reasoner r) {
            return pre((input, ctx) -> {
                ReasoningChain chain = r.reason(input != null ? input.toString() : "");
                return ctx.withReasoning(chain);
            });
        }
    }

    final class Planning {
        private Planning() {}
        @SuppressWarnings("unchecked")
        public static <I, O> Faculty<I, O> of(Planner<O> planner) {
            return pre((input, ctx) -> {
                Plan<O> plan = planner.generatePlan(
                        PlanningRequest.<O>builder().goal((O) input).build());
                return ctx.withPlan(plan);
            });
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
