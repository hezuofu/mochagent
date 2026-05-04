package io.sketch.mochaagents.agent;

/**
 * Exception hierarchy for the agent framework.
 *
 * <pre>
 * AgentException                    — base (retryable, errorCode)
 *   ├── AgentExecutionException     — agent execution failure (step, loop)
 *   ├── AgentToolException          — tool execution failure (name, args)
 *   ├── AgentLLMException           — LLM call failure (statusCode, model)
 *   ├── AgentConfigException        — configuration error
 *   └── AgentPermissionException    — permission denied
 * </pre>
 * @author lanxia39@163.com
 */
public class AgentException extends RuntimeException {
    private final String errorCode;
    private final boolean retryable;

    public AgentException(String message) { this(message, "AGENT_ERROR", false, null); }
    public AgentException(String message, String errorCode) { this(message, errorCode, false, null); }
    public AgentException(String message, String errorCode, boolean retryable) { this(message, errorCode, retryable, null); }
    public AgentException(String message, String errorCode, boolean retryable, Throwable cause) {
        super(message, cause); this.errorCode = errorCode; this.retryable = retryable;
    }

    public String errorCode() { return errorCode; }
    public boolean retryable() { return retryable; }

    // ── Subtypes ──

    public static class ExecutionException extends AgentException {
        private final int stepNumber;
        public ExecutionException(String msg, int step) { super(msg, "EXEC_ERROR", true); this.stepNumber = step; }
        public int stepNumber() { return stepNumber; }
    }

    public static class ToolException extends AgentException {
        private final String toolName;
        public ToolException(String toolName, String msg) { super(msg, "TOOL_ERROR", false); this.toolName = toolName; }
        public ToolException(String toolName, String msg, Throwable cause) { super(msg, "TOOL_ERROR", false, cause); this.toolName = toolName; }
        public String toolName() { return toolName; }
    }

    public static class LLMException extends AgentException {
        private final int statusCode;
        private final String model;
        public LLMException(String msg, int statusCode, String model) { super(msg, "LLM_ERROR", statusCode >= 500 || statusCode == 429); this.statusCode = statusCode; this.model = model; }
        public LLMException(String msg, int statusCode, String model, Throwable cause) { super(msg, "LLM_ERROR", statusCode >= 500 || statusCode == 429, cause); this.statusCode = statusCode; this.model = model; }
        public int statusCode() { return statusCode; }
        public String model() { return model; }
        public boolean isRateLimit() { return statusCode == 429; }
    }

    public static class ConfigException extends AgentException {
        public ConfigException(String msg) { super(msg, "CONFIG_ERROR"); }
    }

    public static class PermissionException extends AgentException {
        private final String toolName;
        public PermissionException(String toolName, String reason) { super("Permission denied for " + toolName + ": " + reason, "PERM_DENIED"); this.toolName = toolName; }
        public String toolName() { return toolName; }
    }
}
