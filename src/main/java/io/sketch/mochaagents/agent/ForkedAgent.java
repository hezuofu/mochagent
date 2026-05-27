// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Forked agent execution — spawns an isolated sub-conversation with its own context.
 * Pattern from claude-code's forkedAgent.ts.
 * @author lanxia39@163.com
 */
public final class ForkedAgent {

    private static final Logger log = LoggerFactory.getLogger(ForkedAgent.class);

    private final ReActAgent agent;
    private final Map<String, Object> sharedContext = new HashMap<>();
    private final Set<String> loadedFiles = new LinkedHashSet<>();
    private io.sketch.mochaagents.memory.MemoryManager parentMemory;

    public ForkedAgent(ReActAgent agent) { this.agent = agent; }

    /** Share a key-value from the parent context. */
    public ForkedAgent share(String key, Object value) { sharedContext.put(key, value); return this; }

    /** Track a file that was loaded in the parent. */
    public ForkedAgent trackFile(String path) { loadedFiles.add(path); return this; }

    /** Wire parent memory for sidechain session transcript (Claude Code pattern). */
    public ForkedAgent withParentMemory(io.sketch.mochaagents.memory.MemoryManager parentMemory) {
        this.parentMemory = parentMemory; return this;
    }

    /**
     * Fork and execute in an isolated context.
     * Returns a CompletableFuture that completes with the result.
     */
    public CompletableFuture<String> fork(String task) {
        return CompletableFuture.supplyAsync(() -> {
            // Build isolated context — merge shared data into prompt
            StringBuilder sb = new StringBuilder();
            if (!sharedContext.isEmpty()) {
                sb.append("Context from parent:\n");
                sharedContext.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
                sb.append("\n");
            }
            if (!loadedFiles.isEmpty()) {
                sb.append("Files available (pre-loaded by parent):\n");
                loadedFiles.forEach(f -> {
                    try {
                        String content = java.nio.file.Files.readString(java.nio.file.Paths.get(f));
                        sb.append("  [File: ").append(f).append("]\n");
                        if (content.length() > 2000) content = content.substring(0, 2000) + "...";
                        sb.append(content).append("\n");
                    } catch (Exception ignored) {}
                });
                sb.append("\n");
            }
            sb.append("Task: ").append(task);

            // Sidechain session tracking (Claude Code subagent transcript pattern)
            String agentId = "agent-" + UUID.randomUUID().toString().substring(0, 8);
            if (parentMemory != null && parentMemory.currentSession() != null) {
                parentMemory.appendToSession("system", "[ForkedAgent " + agentId + "] " + task);
            }

            AgentContext ctx = AgentContext.of(sb.toString());
            log.info("Forked agent {} starting: {}", agentId, task);
            String result = agent.run(ctx);
            log.info("Forked agent {} completed", agentId);

            if (parentMemory != null && parentMemory.currentSession() != null) {
                parentMemory.appendToSession("system",
                        "[ForkedAgent " + agentId + " result] " + (result.length() > 500
                                ? result.substring(0, 500) + "..." : result));
            }
            return result;
        });
    }

    /** Fork with a prepared context. */
    public CompletableFuture<String> fork(AgentContext ctx) {
        return CompletableFuture.supplyAsync(() -> agent.run(ctx));
    }
}
