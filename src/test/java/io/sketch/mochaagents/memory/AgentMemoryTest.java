package io.sketch.mochaagents.memory;

import io.sketch.mochaagents.agent.loop.step.ActionStep;
import io.sketch.mochaagents.agent.loop.step.ContentStep;
import io.sketch.mochaagents.agent.loop.step.PlanningStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AgentMemory including thread safety.
 * @author lanxia39@163.com
 */
class AgentMemoryTest {

    private AgentMemory memory;

    @BeforeEach
    void setUp() {
        memory = new AgentMemory();
    }

    // --- Basic operations ---

    @Test
    void initialStateIsEmpty() {
        assertEquals(0, memory.size());
        assertNull(memory.lastStep());
        assertFalse(memory.hasFinalAnswer());
    }

    @Test
    void resetClearsSteps() {
        memory.reset("test prompt");
        memory.appendTask("task");
        assertEquals(1, memory.size());

        memory.reset();
        assertEquals(0, memory.size());
    }

    @Test
    void resetClearsStepsOnly() {
        memory.reset("sys prompt");
        assertEquals("sys prompt", memory.systemPrompt());
        memory.appendTask("do something");
        assertEquals(1, memory.size());

        memory.reset();
        assertEquals(0, memory.size());
        assertEquals("sys prompt", memory.systemPrompt());
    }

    @Test
    void resetWithPromptReplacesSystemPrompt() {
        memory.reset("old prompt");
        memory.reset("new prompt");
        assertEquals("new prompt", memory.systemPrompt());
        assertEquals(0, memory.size());
    }

    @Test
    void appendTask() {
        memory.appendTask("do something");
        assertEquals(1, memory.size());
        assertTrue(memory.lastStep() instanceof ContentStep cs && cs.isTask());
    }

    @Test
    void appendSystemPrompt() {
        memory.appendSystemPrompt("you are helpful");
        assertEquals(1, memory.size());
        assertTrue(memory.lastStep() instanceof ContentStep cs && cs.isSystemPrompt());
    }

    @Test
    void appendPlanning() {
        memory.appendPlanning("step 1: do X", "model output", 100, 50);
        assertEquals(1, memory.size());
        assertTrue(memory.lastStep() instanceof PlanningStep);
    }

    @Test
    void appendAction() {
        ActionStep step = new ActionStep(1, "", "", "tool_call", "result", null, 100, 50, false);
        memory.appendAction(step);
        assertEquals(1, memory.size());
        assertSame(step, memory.lastStep());
    }

    @Test
    void appendFinalAnswer() {
        memory.appendFinalAnswer("the answer is 42");
        assertEquals(1, memory.size());
        assertTrue(memory.hasFinalAnswer());
    }

    // --- hasFinalAnswer ---

    @Test
    void hasFinalAnswerFalseForActionOnly() {
        memory.appendAction(new ActionStep(1, "", "", "tool", "obs", null, 10, 5, false));
        assertFalse(memory.hasFinalAnswer());
    }

    @Test
    void hasFinalAnswerTrueAfterFinalAnswer() {
        memory.appendAction(new ActionStep(1, "", "", "tool", "obs", null, 10, 5, false));
        memory.appendFinalAnswer("done");
        assertTrue(memory.hasFinalAnswer());
    }

    // --- steps() returns unmodifiable view ---

    @Test
    void stepsReturnsUnmodifiableView() {
        memory.appendTask("test");
        List<?> steps = memory.steps();
        assertThrows(UnsupportedOperationException.class, () -> steps.add(null));
    }

    // --- Thread safety ---

    @Test
    void concurrentAppendsDoNotThrow() throws Exception {
        int threads = 4;
        int appendsPerThread = 100;
        var executor = Executors.newFixedThreadPool(threads);
        var latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < appendsPerThread; i++) {
                        memory.appendAction(new ActionStep(
                                i, "", "", "tool" + tid,
                                "obs" + i, null, 1, 1, false));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        assertEquals(threads * appendsPerThread, memory.size());
    }

    // --- Snapshot ---

    @Test
    void snapshotExtractsEpisodicAndSemanticMemories() {
        memory.appendAction(new ActionStep(1, "", "", "search", "found: X", null, 10, 5, false));
        memory.appendFinalAnswer("result: X");

        List<Memory> snap = memory.snapshot();
        assertEquals(2, snap.size());
        assertTrue(snap.get(0).content().contains("found: X"));
        assertTrue(snap.get(1).content().contains("result: X"));
    }
}
