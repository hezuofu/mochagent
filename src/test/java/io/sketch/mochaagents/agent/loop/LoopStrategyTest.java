// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent.loop;

import io.sketch.mochaagents.agent.event.AgentEvent;
import io.sketch.mochaagents.memory.MemoryManager;
import io.sketch.mochaagents.memory.MemoryProvider;
import io.sketch.mochaagents.agent.event.AgentListener;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.AgentMetadata;
import io.sketch.mochaagents.agent.loop.Reflector;
import io.sketch.mochaagents.agent.loop.strategy.ObservePlanActReflect;
import io.sketch.mochaagents.agent.loop.strategy.ThinkActObserve;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LoopStrategyTest {

    private static StepResult doStep(AtomicInteger counter, int stepNumber, String input,
                                      MemoryManager memory, int maxSteps, boolean produceFinalAnswer) {
        counter.incrementAndGet();
        if (memory != null) {
            memory.appendAction(new io.sketch.mochaagents.agent.loop.step.ActionStep(
                    stepNumber, input, "", "tool", "ok", null, 1, 1, false));
            if (produceFinalAnswer && stepNumber >= maxSteps) {
                memory.appendFinalAnswer("done");
            }
        }
        return StepResult.builder()
                .stepNumber(stepNumber)
                .state(produceFinalAnswer && stepNumber >= maxSteps
                        ? LoopState.COMPLETE : LoopState.ACT)
                .action("step").observation("ok").output("result-" + stepNumber)
                .durationMs(1).build();
    }

    private static final class TaoExecutor implements ThinkActObserve.StepExecutor<String> {
        final AtomicInteger count = new AtomicInteger();
        final int maxSteps;
        final boolean finalAnswer;
        TaoExecutor(int max, boolean fa) { this.maxSteps = max; this.finalAnswer = fa; }
        @Override public StepResult execute(int step, String input, MemoryManager mem) {
            return doStep(count, step, input, mem, maxSteps, finalAnswer);
        }
        int stepsTaken() { return count.get(); }
    }

    private static final class OparExecutor implements ObservePlanActReflect.StepExecutor<String> {
        final AtomicInteger count = new AtomicInteger();
        final int maxSteps;
        final boolean finalAnswer;
        OparExecutor(int max, boolean fa) { this.maxSteps = max; this.finalAnswer = fa; }
        @Override public StepResult execute(int step, String input, MemoryManager mem) {
            return doStep(count, step, input, mem, maxSteps, finalAnswer);
        }
        int stepsTaken() { return count.get(); }
    }

    private static final class MemoryProvidingAgent implements Agent<String, String>, MemoryProvider {
        private final MemoryManager mem = MemoryManager.create();
        @Override public String execute(String input, AgentContext ctx) { return input; }
        @Override public CompletableFuture<String> executeAsync(String input, AgentContext ctx) {
            return CompletableFuture.completedFuture(input);
        }
        @Override public AgentMetadata metadata() { return new AgentMetadata("test"); }
        @Override public void addListener(AgentListener<String, String> l) {}
        @Override public void removeListener(AgentListener<String, String> l) {}
        @Override public MemoryManager memory() { return mem; }
    }

    private static Agent<String, String> dummyAgent() {
        return new MemoryProvidingAgent();
    }

    // --- ThinkActObserve ---

    @Test
    void taoLoopRunsMultipleSteps() {
        TaoExecutor executor = new TaoExecutor(3, true);
        ThinkActObserve<String, String> loop = new ThinkActObserve<>(null, executor);
        loop.run(dummyAgent(), "task", new Termination(4));
        assertTrue(executor.stepsTaken() >= 1);
    }

    @Test
    void taoLoopTerminatesOnCondition() {
        TaoExecutor executor = new TaoExecutor(10, false);
        ThinkActObserve<String, String> loop = new ThinkActObserve<>(null, executor);
        loop.run(dummyAgent(), "task", new Termination(4));
        assertEquals(3, executor.stepsTaken());
    }

    @Test
    void taoLoopWithPlanning() {
        AtomicInteger planCount = new AtomicInteger();
        TaoExecutor executor = new TaoExecutor(2, true);
        ThinkActObserve<String, String> loop = new ThinkActObserve<>(
                (step, input, mem) -> { planCount.incrementAndGet(); return "plan-" + step; },
                executor);
        loop.run(dummyAgent(), "task", new Termination(4));
        assertTrue(planCount.get() >= 1);
    }

    // --- ObservePlanActReflect ---

    @Test
    void oparLoopRunsAllPhases() {
        OparExecutor executor = new OparExecutor(2, true);
        ObservePlanActReflect<String, String> loop = new ObservePlanActReflect<>(
                (step, input, mem) -> "observed " + step,
                (step, input, mem) -> "plan " + step,
                executor, Reflector.noop(), 1);
        loop.run(dummyAgent(), "task", new Termination(4));
        assertTrue(executor.stepsTaken() >= 1);
    }

    @Test
    void oparLoopTerminatesEarly() {
        OparExecutor executor = new OparExecutor(100, false);
        ObservePlanActReflect<String, String> loop = new ObservePlanActReflect<>(
                null, null, executor, Reflector.noop(), 5);
        loop.run(dummyAgent(), "task", new Termination(2));
        assertTrue(executor.stepsTaken() >= 1);
    }

    // --- TerminationCondition ---

    @Test
    void terminationOnError() {
        Termination cond = new Termination(100);
        StepResult errorResult = StepResult.builder().state(LoopState.ERROR)
                .error("something failed").build();
        assertTrue(cond.test(0, errorResult, null));
    }

    @Test
    void terminationOnMaxSteps() {
        Termination cond = new Termination(4);
        assertFalse(cond.test(0,
                StepResult.builder().state(LoopState.ACT).build(), null));
    }
}
