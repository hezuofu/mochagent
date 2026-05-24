// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal tool — name, description, call, schema, security.
 *
 * <p>Implementors need only {@link #getName}, {@link #getDescription},
 * {@link #call}, and {@link #getSecurityLevel}. Everything else has
 * sensible defaults.
  * @author lanxia39@163.com
 */
public interface Tool {

    // ── Core (abstract) ──

    String getName();
    String getDescription();
    Object call(Map<String, Object> arguments);

    // ── Schema ──

    /** Full JSON Schema. Override for typed inputs; default is string-in, string-out. */
    default ToolSchema getSchema() {
        return ToolSchema.inputOnly(Collections.emptyMap());
    }

    /** @deprecated use {@link #getSchema()} */
    default Map<String, ToolInput> getInputs() { return Collections.emptyMap(); }
    /** @deprecated use {@link #getSchema()} */
    @Deprecated default String getOutputType() { return "any"; }

    // ── Security ──

    default SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
    default boolean isReadOnly() { return false; }
    default boolean isDestructive() { return false; }
    default boolean isEnabled() { return true; }

    // ── Validation ──

    default ValidationResult validateInput(Map<String, Object> arguments) { return ValidationResult.valid(); }
    default PermissionResult checkPermissions(Map<String, Object> arguments) { return PermissionResult.allow(arguments); }

    // ── Calling ──

    default CompletableFuture<Object> callAsync(Map<String, Object> arguments) {
        return CompletableFuture.supplyAsync(() -> call(arguments));
    }

    // ── Presentation ──

    default String formatResult(Object output, String toolUseId) {
        return output != null ? output.toString() : "";
    }

    default String describeActivity(Map<String, Object> args) {
        return getName() + (args != null && !args.isEmpty() ? "(" + args.keySet() + ")" : "");
    }

    default boolean isConcurrencySafe() { return false; }
    default List<String> getAliases() { return Collections.emptyList(); }
    default String getSearchHint() { return ""; }

    enum SecurityLevel { LOW, MEDIUM, HIGH, CRITICAL }
}
