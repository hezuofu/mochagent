// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.plugin;

import java.util.List;

/**
 * Base interface for ALL plugins — memory, skill, tool, provider, loop.
 *
 * <p>Every plugin provides zero or more {@link ExtensionPoint}s. The plugin
 * manager discovers, loads, and activates plugins at runtime.
 *
 * <pre>{@code
 * public class ChromaMemoryPlugin implements Plugin {
 *     public String name() { return "chroma-memory"; }
 *     public List<ExtensionPoint<?>> extensions() {
 *         return List.of(ExtensionPoint.of("MEMORY", new ChromaStore()));
 *     }
 * }
 * }</pre>
 */
public interface Plugin {

    /** Unique plugin identifier. */
    String name();

    /** Human-readable description. */
    default String description() { return ""; }

    /** Plugin version. */
    default String version() { return "1.0"; }

    /** Extension points this plugin provides. */
    List<ExtensionPoint<?>> extensions();

    /** Called when plugin is activated. */
    default void onActivate() {}

    /** Called when plugin is deactivated. */
    default void onDeactivate() {}
}
