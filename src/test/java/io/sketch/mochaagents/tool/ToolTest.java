package io.sketch.mochaagents.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Tool interface contract and ToolInput.
 * @author lanxia39@163.com
 */
class ToolTest {

    // --- ToolInput factory methods ---

    @Test
    void stringInput() {
        ToolInput input = ToolInput.string("a string param");
        assertEquals("string", input.type());
        assertEquals("a string param", input.description());
        assertFalse(input.nullable());
    }

    @Test
    void integerInput() {
        ToolInput input = ToolInput.integer("count");
        assertEquals("integer", input.type());
        assertFalse(input.nullable());
    }

    @Test
    void anyInputIsNullable() {
        ToolInput input = ToolInput.any("any value");
        assertEquals("any", input.type());
        assertTrue(input.nullable());
    }

    // --- Tool defaults (fail-closed safety) ---

    @Test
    void defaultIsReadOnlyIsFalse() {
        Tool tool = new NoopTool();
        assertFalse(tool.isReadOnly());
    }

    @Test
    void defaultIsConcurrencySafeIsFalse() {
        Tool tool = new NoopTool();
        assertFalse(tool.isConcurrencySafe());
    }

    @Test
    void defaultIsDestructiveIsFalse() {
        Tool tool = new NoopTool();
        assertFalse(tool.isDestructive());
    }

    @Test
    void defaultIsEnabledIsTrue() {
        Tool tool = new NoopTool();
        assertTrue(tool.isEnabled());
    }

    @Test
    void defaultValidationPasses() {
        Tool tool = new NoopTool();
        assertTrue(tool.validateInput(Map.of()).isValid());
    }

    @Test
    void defaultPermissionAllows() {
        Tool tool = new NoopTool();
        assertTrue(tool.checkPermissions(Map.of()).isAllowed());
    }

    @Test
    void defaultAliasesEmpty() {
        Tool tool = new NoopTool();
        assertTrue(tool.getAliases().isEmpty());
    }

    // --- Async call delegates to sync ---

    @Test
    void callAsyncDelegatesToSync() throws Exception {
        Tool tool = new NoopTool();
        CompletableFuture<Object> future = tool.callAsync(Map.of());
        assertEquals("noop-result", future.get(5, TimeUnit.SECONDS));
    }

    // --- formatResult ---

    @Test
    void formatResultReturnsToString() {
        Tool tool = new NoopTool();
        assertEquals("hello", tool.formatResult("hello", "id1"));
    }

    @Test
    void formatResultHandlesNull() {
        Tool tool = new NoopTool();
        assertEquals("", tool.formatResult(null, "id1"));
    }

    // --- Minimal tool implementation for testing ---

    static class NoopTool implements Tool {
        @Override public String getName() { return "noop"; }
        @Override public String getDescription() { return "does nothing"; }
        @Override public Map<String, ToolInput> getInputs() { return Map.of(); }
        @Override public String getOutputType() { return "string"; }
        @Override public Object call(Map<String, Object> arguments) { return "noop-result"; }
        @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
    }
}
