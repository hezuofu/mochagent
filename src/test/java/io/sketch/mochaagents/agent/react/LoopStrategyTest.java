package io.sketch.mochaagents.agent.react;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.AgentListener;
import io.sketch.mochaagents.agent.AgentMetadata;
import io.sketch.mochaagents.agent.MemoryProvider;
import io.sketch.mochaagents.agent.react.ReflectionEngine;
import io.sketch.mochaagents.agent.react.strategy.ObservePlanActReflect;
import io.sketch.mochaagents.agent.react.strategy.ThinkActObserve;
import io.sketch.mochaagents.memory.AgentMemory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LoopStrategyTest {

    private static StepResult doStep(AtomicInteger counter, int stepNumber, String input,
                                      AgentMemory memory, int maxSteps, boolean produceFinalAnswer) {
        counter.incrementAndGet();
        if (memory != null) {
            memory.appendAction(new io.sketch.mochaagents.agent.react.step.ActionStep(
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
        @Override public StepResult execute(int step, String input, AgentMemory mem) {
            return doStep(count, step, input, mem, maxSteps, finalAnswer);
        }
        int stepsTaken() { return count.get(); }
    }

    private static final class OparExecutor implements ObservePlanActReflect.StepExecutor<String> {
        final AtomicInteger count = new AtomicInteger();
        final int maxSteps;
        final boolean finalAnswer;
        OparExecutor(int max, boolean fa) { this.maxSteps = max; this.finalAnswer = fa; }
        @Override public StepResult execute(int step, String input, AgentMemory mem) {
            return doStep(count, step, input, mem, maxSteps, finalAnswer);
        }
        int stepsTaken() { return count.get(); }
    }

    private static final class MemoryProvidingAgent implements Agent<String, String>, MemoryProvider {
        private final AgentMemory mem = new AgentMemory();
        @Override public String execute(String input, AgentContext ctx) { return input; }
        @Override public CompletableFuture<String> executeAsync(String input, AgentContext ctx) {
            return CompletableFuture.completedFuture(input);
        }
        @Override public AgentMetadata metadata() { return AgentMetadata.builder().name("test").build(); }
        @Override public void addListener(AgentListener<String, String> l) {}
        @Override public void removeListener(AgentListener<String, String> l) {}
        @Override public AgentMemory memory() { return mem; }
    }

    private static Agent<String, String> dummyAgent() {
        return new MemoryProvidingAgent();
    }

    // --- ThinkActObserve ---

    @Test
    void taoLoopRunsMultipleSteps() {
        TaoExecutor executor = new TaoExecutor(3, true);
        ThinkActObserve<String, String> loop = new ThinkActObserve<>(null, executor);
        loop.run(dummyAgent(), "task", Termination.maxSteps(5));
        assertTrue(executor.stepsTaken() >= 1);
    }

    @Test
    void taoLoopTerminatesOnCondition() {
        TaoExecutor executor = new TaoExecutor(10, false);
        ThinkActObserve<String, String> loop = new ThinkActObserve<>(null, executor);
        loop.run(dummyAgent(), "task", Termination.maxSteps(3));
        assertEquals(3, executor.stepsTaken());
    }

    @Test
    void taoLoopWithPlanning() {
        AtomicInteger planCount = new AtomicInteger();
        TaoExecutor executor = new TaoExecutor(2, true);
        ThinkActObserve<String, String> loop = new ThinkActObserve<>(
                (step, input, mem) -> { planCount.incrementAndGet(); return "plan-" + step; },
                executor);
        loop.run(dummyAgent(), "task", Termination.maxSteps(3));
        assertTrue(planCount.get() >= 1);
    }

    // --- ObservePlanActReflect ---

    @Test
    void oparLoopRunsAllPhases() {
        OparExecutor executor = new OparExecutor(2, true);
        ObservePlanActReflect<String, String> loop = new ObservePlanActReflect<>(
                (step, input, mem) -> "observed " + step,
                (step, input, mem) -> "plan " + step,
                executor, ReflectionEngine.noop(), 1);
        loop.run(dummyAgent(), "task", Termination.maxSteps(3));
        assertTrue(executor.stepsTaken() >= 1);
    }

    @Test
    void oparLoopTerminatesEarly() {
        OparExecutor executor = new OparExecutor(100, false);
        ObservePlanActReflect<String, String> loop = new ObservePlanActReflect<>(
                null, null, executor, ReflectionEngine.noop(), 5);
        loop.run(dummyAgent(), "task", Termination.maxSteps(2));
        assertEquals(2, executor.stepsTaken());
    }

    // --- TerminationCondition ---

    @Test
    void terminationOnError() {
        TerminationCondition cond = Termination.onError();
        StepResult errorResult = StepResult.builder().state(LoopState.ERROR)
                .error("something failed").build();
        assertTrue(cond.shouldTerminate(errorResult));
    }

    @Test
    void terminationOnMaxSteps() {
        TerminationCondition cond = Termination.maxSteps(3);
        assertFalse(cond.shouldTerminate(
                StepResult.builder().state(LoopState.ACT).build()));
    }
}
