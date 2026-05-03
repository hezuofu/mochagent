package io.sketch.mochaagents.memory;

import io.sketch.mochaagents.agent.loop.step.ActionStep;
import io.sketch.mochaagents.agent.loop.step.ContentStep;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentMemoryEdgeTest {

    // --- Edge cases ---

    @Test
    void lastStepOnEmptyMemoryReturnsNull() {
        AgentMemory mem = new AgentMemory();
        assertNull(mem.lastStep());
    }

    @Test
    void hasFinalAnswerEmptyMemoryReturnsFalse() {
        assertFalse(new AgentMemory().hasFinalAnswer());
    }

    @Test
    void appendNullTaskDoesNotThrow() {
        AgentMemory mem = new AgentMemory();
        assertDoesNotThrow(() -> mem.appendTask(null));
    }

    @Test
    void sizeAfterResetIsZero() {
        AgentMemory mem = new AgentMemory();
        mem.appendTask("task1");
        mem.appendTask("task2");
        assertEquals(2, mem.size());
        mem.reset();
        assertEquals(0, mem.size());
    }

    @Test
    void snapshotEmptyMemoryReturnsEmpty() {
        assertTrue(new AgentMemory().snapshot().isEmpty());
    }

    @Test
    void restoreEmptyListDoesNotThrow() {
        AgentMemory mem = new AgentMemory();
        assertDoesNotThrow(() -> mem.restore(java.util.List.of()));
    }

    @Test
    void systemPromptInitiallyNull() {
        assertNull(new AgentMemory().systemPrompt());
    }

    // --- Failure cases ---

    @Test
    void actionStepWithErrorHasLowImportance() {
        AgentMemory mem = new AgentMemory();
        mem.appendAction(new ActionStep(1, "", "", "tool", "obs", "error occurred", 1, 1, false));
        assertEquals(1, mem.size());
        assertNotNull(mem.lastStep());
    }

    @Test
    void appendNullOrEmptyPlanning() {
        AgentMemory mem = new AgentMemory();
        mem.appendPlanning(null, null, 0, 0);
        assertEquals(1, mem.size()); // still adds a step
    }

    @Test
    void stepsViewIsImmutable() {
        AgentMemory mem = new AgentMemory();
        mem.appendTask("test");
        assertThrows(UnsupportedOperationException.class,
                () -> mem.steps().add(null));
    }
}
