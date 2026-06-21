// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.memory;

import io.sketch.mochaagents.agent.loop.step.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryManagerTest {

    @Test void startsEmpty() {
        var m = MemoryManager.create();
        assertEquals(0, m.stepCount());
        assertFalse(m.hasFinalAnswer());
        assertEquals("", m.workingContext());
        assertEquals("", m.globalContext());
    }

    @Test void remembersSteps() {
        var m = MemoryManager.create();
        m.appendTask("task");
        m.appendSystemPrompt("prompt");
        assertEquals(2, m.stepCount());
    }

    @Test void checkpointedKeyInfoAndSop() {
        var m = MemoryManager.create();
        m.checkpoint("Project uses Java 17", "java_sop");
        assertTrue(m.workingContext().contains("Java 17"));
        assertTrue(m.workingContext().contains("java_sop"));
    }

    @Test void detectsFinalAnswer() {
        var m = MemoryManager.create();
        m.appendFinalAnswer("done");
        assertTrue(m.hasFinalAnswer());
    }

    @Test void resetClearsSteps() {
        var m = MemoryManager.create();
        m.appendTask("task");
        m.appendFinalAnswer("done");
        m.reset("new prompt");
        assertEquals(0, m.stepCount());
        assertFalse(m.hasFinalAnswer());
    }

    @Test void checkpointSurvivesReset() {
        var m = MemoryManager.create();
        m.checkpoint("key", "sop");
        m.reset("new prompt");
        assertTrue(m.workingContext().contains("key")); // working memory persists
    }

    @Test void withGlobalMemory() {
        var m = MemoryManager.create().withGlobalMemory();
        m.settle("Project uses Maven");
        assertTrue(m.globalContext().contains("Maven"));
    }

    @Test void withPlugin() {
        var m = MemoryManager.create();
        m.withPlugin(new MemoryPlugin() {
            @Override public String buildSystemPrompt() { return "plugin context"; }
        });
        assertTrue(m.buildPluginPrompt().contains("plugin context"));
    }
}
