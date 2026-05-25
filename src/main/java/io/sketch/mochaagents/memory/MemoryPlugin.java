// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.memory;
import java.util.stream.Stream;

import io.sketch.mochaagents.plugin.ExtensionPoint;
import io.sketch.mochaagents.plugin.Plugin;

import java.util.List;

/**
 * Memory plugin — extends the unified Plugin system for memory backends.
 *
 * <p>Implementations register via {@link MemoryManager#withPlugin(MemoryPlugin)}
 * or via the plugin loader as ExtensionPoint("MEMORY", ...).
 * Only ONE external plugin is recommended (prevents schema bloat).
  * @author lanxia39@163.com
 */
public interface MemoryPlugin extends Plugin {

    default String name() { return getClass().getSimpleName(); }
    default String buildSystemPrompt() { return ""; }
    default String prefetch(String userMessage) { return ""; }
    default void sync(String userMessage, String assistantResponse) {}
    default void onTurnStart(int turnCount, String userMessage) {}

    /** Expose this plugin as a MEMORY extension point for the unified plugin system. */
    default Stream<ExtensionPoint<?>> extensions() {
        return Stream.of(ExtensionPoint.memory(this, 0));
    }
}
