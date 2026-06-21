// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.learn;

import io.sketch.mochaagents.memory.MemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Background reviewer — hermes-agent _spawn_background_review pattern.
 *
 * <p>After task completion, spawns a background review to distill
 * learnings into memory. Never modifies the main conversation.
 *
 * @author lanxia39@163.com
 */
public class BackgroundReviewer {

    private static final Logger log = LoggerFactory.getLogger(BackgroundReviewer.class);

    private final MemoryManager memory;

    public BackgroundReviewer(MemoryManager memory) { this.memory = memory; }

    /** Spawn background review after task completes. */
    public void review(String task, String result) {
        CompletableFuture.runAsync(() -> {
            try {
                // Distill verified facts from the task result
                var snapshots = memory.snapshot();
                if (snapshots.isEmpty()) {
                    return;
                }

                // Save episodic memory
                for (var entry : snapshots) {
                    memory.save(entry);
                }

                // If agent produced a final answer, settle it
                if (memory.hasFinalAnswer()) {
                    String summary = "Task: " + (task.length() > 200 ? task.substring(0, 200) + "..." : task)
                            + " | Result: " + (result != null && result.length() > 200 ? result.substring(0, 200) + "..." : result);
                    memory.settle(summary);
                }

                log.debug("Background review completed: {} entries", snapshots.size());
            } catch (Exception e) {
                log.warn("Background review failed: {}", e.getMessage());
            }
        });
    }

    public MemoryManager memory() { return memory; }
}
