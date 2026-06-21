// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.plugin;

import java.util.stream.Stream;

/**
 * Plugin — provide extension points. Metadata from {@link PluginMeta} annotation.
 *
 * <pre>{@code
 * @PluginInfo(name = "my-tools", description = "Custom tool set")
 * class MyPlugin implements Plugin {
 *     public Stream<ExtensionPoint<?>> extensions() {
 *         return Stream.of(ExtensionPoint.tool(new MyTool(), 0));
 *     }
 * }
 * }</pre>
 *
 * @author lanxia39@163.com
 */
@FunctionalInterface
public interface Plugin {
    Stream<ExtensionPoint<?>> extensions();

    /** Derived from @PluginInfo annotation. */
    default String name() {
        PluginMeta info = getClass().getAnnotation(PluginMeta.class);
        return info != null ? info.name() : getClass().getSimpleName();
    }

    default String description() {
        PluginMeta info = getClass().getAnnotation(PluginMeta.class);
        return info != null ? info.description() : "";
    }

    default String version() {
        PluginMeta info = getClass().getAnnotation(PluginMeta.class);
        return info != null ? info.version() : "1.0";
    }
}
