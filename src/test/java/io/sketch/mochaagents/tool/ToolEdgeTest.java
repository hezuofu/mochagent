package io.sketch.mochaagents.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolEdgeTest {

    // --- Edge cases ---

    @Test
    void toolInputWithEmptyDescription() {
        ToolInput input = new ToolInput("string", "", false);
        assertEquals("", input.description());
    }

    @Test
    void validationResultInvalidWithMessage() {
        ValidationResult r = ValidationResult.invalid("missing field", 400);
        assertFalse(r.isValid());
        assertEquals("missing field", r.getMessage());
        assertEquals(400, r.getErrorCode());
    }

    @Test
    void validationResultValidWithMeta() {
        ValidationResult r = ValidationResult.valid(Map.of("hint", "ok"));
        assertTrue(r.isValid());
        assertNotNull(r.getMeta());
    }

    @Test
    void validationResultInvalidWithMeta() {
        ValidationResult r = ValidationResult.invalid("bad", 422, Map.of("field", "name"));
        assertFalse(r.isValid());
        assertEquals("bad", r.getMessage());
        assertEquals(422, r.getErrorCode());
    }

    @Test
    void permissionResultDeny() {
        PermissionResult r = PermissionResult.deny("not authorized");
        assertTrue(r.isDenied());
        assertFalse(r.isAllowed());
        assertFalse(r.requiresUserInteraction());
    }

    @Test
    void permissionResultAsk() {
        PermissionResult r = PermissionResult.ask("confirm deletion");
        assertTrue(r.requiresUserInteraction());
        assertFalse(r.isAllowed());
        assertFalse(r.isDenied());
    }

    @Test
    void permissionResultAllowWithReason() {
        PermissionResult r = PermissionResult.allow(Map.of(), "safe operation");
        assertTrue(r.isAllowed());
        assertEquals("safe operation", r.getDecisionReason());
    }

    @Test
    void permissionResultDenyWithReason() {
        PermissionResult r = PermissionResult.deny("blocked", "harmful");
        assertEquals("harmful", r.getDecisionReason());
    }

    @Test
    void permissionResultAskWithReason() {
        PermissionResult r = PermissionResult.ask("approve?", "high risk");
        assertEquals("high risk", r.getDecisionReason());
    }

    // --- Tool safety defaults ---

    @Test
    void toolSafetyDefaultsAreFalse() {
        Tool t = new Tool() {
            @Override public String getName() { return "test"; }
            @Override public String getDescription() { return "test"; }
            @Override public Map<String, ToolInput> getInputs() { return Map.of(); }
            @Override public String getOutputType() { return "string"; }
            @Override public Object call(Map<String, Object> args) { return null; }
            @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
        };
        assertFalse(t.isReadOnly());
        assertFalse(t.isConcurrencySafe());
        assertFalse(t.isDestructive());
        assertTrue(t.isEnabled());
    }

    @Test
    void toolDefaultAliasesEmpty() {
        Tool t = new Tool() {
            @Override public String getName() { return "test"; }
            @Override public String getDescription() { return "test"; }
            @Override public Map<String, ToolInput> getInputs() { return Map.of(); }
            @Override public String getOutputType() { return "string"; }
            @Override public Object call(Map<String, Object> args) { return null; }
            @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
        };
        assertTrue(t.getAliases().isEmpty());
        assertEquals("test", t.getUserFacingName());
    }
}
