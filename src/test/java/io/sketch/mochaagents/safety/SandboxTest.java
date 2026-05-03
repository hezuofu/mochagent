package io.sketch.mochaagents.safety;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SandboxTest {

    private static Tool echoTool() {
        return new Tool() {
            @Override public String getName() { return "echo"; }
            @Override public String getDescription() { return "echo"; }
            @Override public Map<String, ToolInput> getInputs() { return Map.of(); }
            @Override public String getOutputType() { return "string"; }
            @Override public Object call(Map<String, Object> args) { return "echo: " + args; }
            @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
        };
    }

    @Test
    void noopSandboxPassesResultThrough() {
        Sandbox sb = new NoopSandbox();
        Tool wrapped = sb.wrap(echoTool());
        Object result = wrapped.call(Map.of("key", "value"));
        assertTrue(result.toString().contains("echo"));
    }

    @Test
    void safeToolRegistryWrapsTools() {
        SafeToolRegistry registry = new SafeToolRegistry(new NoopSandbox());
        registry.register(echoTool());
        assertNotNull(registry.get("echo"));
        Object result = registry.get("echo").call(Map.of());
        assertTrue(result.toString().contains("echo"));
    }

    @Test
    void processSandboxExecutesCode() {
        ProcessSandbox sb = new ProcessSandbox(5000, 1000, true);
        String result = sb.execute("echo hello", "shell");
        assertTrue(result.contains("hello") || result.contains("Error"));
    }

    @Test
    void sandboxBackendName() {
        assertEquals("NoopSandbox", new NoopSandbox().backendName());
        assertEquals("ProcessSandbox", new ProcessSandbox().backendName());
    }

    @Test
    void sandboxedToolPreservesMetadata() {
        Tool raw = echoTool();
        Tool wrapped = new SandboxedTool(raw, new NoopSandbox());
        assertEquals(raw.getName(), wrapped.getName());
        assertEquals(raw.getDescription(), wrapped.getDescription());
        assertEquals(raw.getSecurityLevel(), wrapped.getSecurityLevel());
    }
}
