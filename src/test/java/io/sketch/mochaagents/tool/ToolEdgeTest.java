// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

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
        assertEquals("test", t.getName());
    }

    // ── ToolSchema validation (Pydantic/Zod pattern) ──

    @Test void schemaValidateRequiredFieldMissing() {
        var schema = ToolSchema.builder()
                .inputType("object")
                .inputRequired("path")
                .build();
        var result = schema.validate(Map.of(), Map.class);
        assertFalse(result.isValid());
        assertTrue(result.getMessage().contains("Missing required"));
    }

    @Test void schemaValidateRequiredFieldPresent() {
        var schema = ToolSchema.builder()
                .inputType("object")
                .inputRequired("path")
                .build();
        var result = schema.validate(Map.of("path", "/tmp"), Map.class);
        assertTrue(result.isValid());
    }

    @Test void schemaValidateTypeMismatch() {
        var schema = ToolSchema.builder()
                .inputType("object")
                .inputProperty("count", "integer", "A number", true)
                .build();
        var result = schema.validate(Map.of("count", "not-a-number"), Map.class);
        assertFalse(result.isValid());
        assertTrue(result.getMessage().contains("expected integer"));
    }

    @Test void schemaValidateTypeMatches() {
        var schema = ToolSchema.builder()
                .inputType("object")
                .inputProperty("name", "string", "Name", true)
                .inputProperty("age", "integer", "Age", false)
                .build();
        var result = schema.validate(Map.of("name", "Alice", "age", 30), Map.class);
        assertTrue(result.isValid());
    }

    @Test void schemaValidateConvertsToRecord() {
        record TestInput(String name, int count) {}
        var schema = ToolSchema.builder()
                .inputType("object")
                .inputProperty("name", "string", "Name", true)
                .inputProperty("count", "integer", "Count", true)
                .build();
        var result = schema.validate(Map.of("name", "test", "count", 42), TestInput.class);
        assertTrue(result.isValid());
        assertTrue(result.hasTypedValue());
        TestInput typed = result.typedValue();
        assertEquals("test", typed.name());
        assertEquals(42, typed.count());
    }

    @Test void schemaValidateInvalidConversionReturnsError() {
        record NumInput(int value) {}
        var schema = ToolSchema.builder()
                .inputType("object")
                .inputProperty("value", "integer", "A number", true)
                .build();
        var result = schema.validate(Map.of("value", "abc"), NumInput.class);
        assertFalse(result.isValid());
    }

    @Test void schemaBuilderCreatesValidSchema() {
        var schema = ToolSchema.builder()
                .inputType("object")
                .inputProperty("file", "string", "File path", true)
                .outputType("string")
                .outputDescription("File contents")
                .build();
        assertNotNull(schema.getInputSchema());
        assertNotNull(schema.getOutputSchema());
        assertEquals("object", schema.getInputSchema().get("type"));
    }
}
