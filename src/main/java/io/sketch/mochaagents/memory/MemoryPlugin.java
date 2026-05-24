// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.memory;

/**
 * Pluggable memory provider — hermes-agent MemoryProvider pattern.
 *
 * <p>Register via {@link MemoryManager#withPlugin(MemoryPlugin)}.
 * Only ONE external plugin is recommended (prevents schema bloat).
 */
public interface MemoryPlugin {

    /** Short identifier (e.g. "chroma", "pinecone"). */
    default String name() { return getClass().getSimpleName(); }

    /** Build memory section for system prompt. Return "" if nothing. */
    default String buildSystemPrompt() { return ""; }

    /** Pre-fetch relevant context before Model call. */
    default String prefetch(String userMessage) { return ""; }

    /** Sync conversation after Model response. */
    default void sync(String userMessage, String assistantResponse) {}

    /** Called at start of each turn. */
    default void onTurnStart(int turnCount, String userMessage) {}
}
