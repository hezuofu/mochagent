// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.plugin;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@PluginInfo(name = "test-plugin", description = "Test plugin", version = "2.0")
class TestPlugin implements Plugin {
    public Stream<ExtensionPoint<?>> extensions() { return Stream.empty(); }
}

class PluginTest {

    @Test void pluginInfoAnnotationReadable() {
        var info = TestPlugin.class.getAnnotation(PluginInfo.class);
        assertNotNull(info);
        assertEquals("test-plugin", info.name());
        assertEquals("Test plugin", info.description());
        assertEquals("2.0", info.version());
    }

    @Test void pluginDefaultsFromAnnotation() {
        var p = new TestPlugin();
        assertEquals("test-plugin", p.name());
        assertEquals("Test plugin", p.description());
        assertEquals("2.0", p.version());
    }

    @Test void lambdaPlugin() {
        Plugin p = () -> Stream.of(ExtensionPoint.tool(new io.sketch.mochaagents.tool.internal.CalculatorTool(), 0));
        assertEquals(1, p.extensions().count());
    }

    @Test void managerRegistersPlugin() {
        var pm = new PluginManager();
        pm.registerPlugin(new TestPlugin());
        var result = pm.getPlugins();
        assertNotNull(result);
        assertEquals(1, result.enabled().size());
        assertEquals("test-plugin", result.enabled().get(0).name());
    }

    @Test void managerRejectsMissingAnnotation() {
        var pm = new PluginManager();
        assertThrows(IllegalArgumentException.class, () -> pm.registerPlugin(() -> Stream.empty()));
    }
}
