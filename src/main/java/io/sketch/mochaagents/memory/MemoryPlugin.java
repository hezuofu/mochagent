// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.memory;

import io.sketch.mochaagents.plugin.ExtensionPoint;
import io.sketch.mochaagents.plugin.Plugin;

import java.util.stream.Stream;

/**
 * Memory plugin — extends {@link MemoryProvider} with Plugin discovery.
 *
 * <p>Implementations register via {@link MemoryManager#withPlugin(MemoryPlugin)}
 * or via the plugin loader as ExtensionPoint("MEMORY", ...).
 * Only ONE external plugin is recommended (prevenits schema bloat).
 *
 * <p>All memory lifecycle hooks (buildSystemPrompt, prefetch, sync, onTurnStart)
 * are inherited from {@link MemoryProvider}.
 *
 * @author lanxia39@163.com
 */
public interface MemoryPlugin extends MemoryProvider, Plugin {

    /** Resolve the diamond: both MemoryProvider and Plugin define default name(). Use MemoryProvider's. */
    @Override
    default String name() { return MemoryProvider.super.name(); }

    /** Expose this plugin as a MEMORY extension point for the unified plugin system. */
    @Override
    default Stream<ExtensionPoint<?>> extensions() {
        return Stream.of(ExtensionPoint.memory(this, 0));
    }
}
