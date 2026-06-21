// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.memory;

import io.sketch.mochaagents.agent.Agent;

/**
 * Memory plugin — hermes-agent compatible provider interface.
  * @author lanxia39@163.com
 */
public interface MemoryProvider {

    default String name() { return getClass().getSimpleName(); }
    default String buildSystemPrompt() { return ""; }
    default String prefetch(String userMessage) { return ""; }
    default void sync(String userMessage, String assistantResponse) {}
    default void onTurnStart(int turnCount, String userMessage) {}
    default MemoryManager memory() { return null; }

    static MemoryManager of(Agent<?, ?> agent) {
        return agent instanceof MemoryProvider mp ? mp.memory() : null;
    }
}
