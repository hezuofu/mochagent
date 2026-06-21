// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents;

/**
 * Root exception for the entire MochaAgents framework.
 *
 * <p>All framework exceptions extend this class. Standard Java exceptions
 * (IllegalArgumentException, etc.) remain for pure logic errors.
  * @author lanxia39@163.com
 */
public class MochaException extends RuntimeException {
    private final String errorCode;
    private final boolean retryable;

    public MochaException(String message) { this(message, "MOCHA_ERROR", false, null); }
    public MochaException(String message, String errorCode) { this(message, errorCode, false, null); }
    public MochaException(String message, String errorCode, boolean retryable) { this(message, errorCode, retryable, null); }
    public MochaException(String message, String errorCode, boolean retryable, Throwable cause) {
        super(message, cause); this.errorCode = errorCode; this.retryable = retryable;
    }

    public String errorCode() { return errorCode; }
    public boolean isRetryable() { return retryable; }

    // ── Subtypes ──

    /** Tool execution failure with error category (hermes-agent ToolFailure pattern). */
    public static class ToolException extends MochaException {
        public enum Category { VALIDATION, TIMEOUT, EXECUTION, PERMISSION, UNKNOWN }

        private final String toolName;
        private final Category category;

        public ToolException(String toolName, String msg) { this(toolName, msg, Category.EXECUTION, false, null); }
        public ToolException(String toolName, String msg, Throwable cause) { this(toolName, msg, Category.EXECUTION, false, cause); }
        public ToolException(String toolName, String msg, Category category, boolean retryable, Throwable cause) {
            super(msg, "TOOL_ERROR", retryable, cause);
            this.toolName = toolName; this.category = category;
        }

        public String toolName() { return toolName; }
        public Category category() { return category; }

        public static ToolException validation(String tool, String msg) { return new ToolException(tool, msg, Category.VALIDATION, false, null); }
        public static ToolException timeout(String tool) { return new ToolException(tool, "Tool '" + tool + "' timed out", Category.TIMEOUT, true, null); }
        public static ToolException permission(String tool, String msg) { return new ToolException(tool, msg, Category.PERMISSION, false, null); }
        public static ToolException execution(String tool, String msg, Throwable cause) { return new ToolException(tool, msg, Category.EXECUTION, false, cause); }
    }

    /** Model API call failure. */
    public static class LlmException extends MochaException {
        private final int statusCode;
        private final String model;
        public LlmException(String msg, int statusCode, String model) { super(msg, "LLM_ERROR", statusCode >= 500 || statusCode == 429); this.statusCode = statusCode; this.model = model; }
        public LlmException(String msg, int statusCode, String model, Throwable cause) { super(msg, "LLM_ERROR", statusCode >= 500 || statusCode == 429, cause); this.statusCode = statusCode; this.model = model; }
        public int statusCode() { return statusCode; }
        public String model() { return model; }
        public boolean isRateLimit() { return statusCode == 429; }
    }

    /** Configuration error. */
    public static class ConfigException extends MochaException {
        public ConfigException(String msg) { super(msg, "CONFIG_ERROR"); }
    }

    /** Permission denied. */
    public static class PermissionException extends MochaException {
        private final String toolName;
        public PermissionException(String toolName, String reason) { super("Permission denied for " + toolName + ": " + reason, "PERM_DENIED"); this.toolName = toolName; }
        public String toolName() { return toolName; }
    }
}
