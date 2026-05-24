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

    /** Tool execution failure. */
    public static class ToolException extends MochaException {
        private final String toolName;
        public ToolException(String toolName, String msg) { super(msg, "TOOL_ERROR"); this.toolName = toolName; }
        public ToolException(String toolName, String msg, Throwable cause) { super(msg, "TOOL_ERROR", false, cause); this.toolName = toolName; }
        public String toolName() { return toolName; }
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
