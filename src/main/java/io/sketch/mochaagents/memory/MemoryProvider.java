// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.memory;

import io.sketch.mochaagents.agent.Agent;

/**
 * Memory plugin — hermes-agent compatible provider interface.
 *
 * <p>Implementations can be registered with {@link MemoryManager} to inject
 * context pre-turn and sync post-turn. Only ONE external provider is allowed
 * (prevents tool schema bloat).
 *
 * <p>Built-in: {@link MemoryManager} uses this for the default memory store.
 */
public interface MemoryProvider {

    // ── Plugin identity ──

    /** Unique name for this provider (e.g. "builtin", "chroma", "pinecone"). */
    default String name() { return getClass().getSimpleName(); }

    // ── System prompt injection ──

    /** Build the memory section of the system prompt. Return "" if none. */
    default String buildSystemPrompt() { return ""; }

    // ── Turn lifecycle ──

    /** Pre-fetch context before the LLM call. Return "" if none. */
    default String prefetch(String userMessage) { return ""; }

    /** Sync conversation after the LLM response. */
    default void sync(String userMessage, String assistantResponse) {}

    /** Called at the start of each turn. */
    default void onTurnStart(int turnCount, String userMessage) {}

    // ── Legacy (agent memory access) ──

    /** @deprecated use {@link MemoryManager} for persistence */
    @Deprecated
    default MemoryManager memory() { return null; }

    /** Extract AgentMemory from an agent, or null. */
    static MemoryManager of(Agent<?, ?> agent) {
        return agent instanceof MemoryProvider mp ? mp.memory() : null;
    }
}
