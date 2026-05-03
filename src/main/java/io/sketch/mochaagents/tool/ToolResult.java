package io.sketch.mochaagents.tool;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 工具执行结果 — 对齐 claude-code 的 ToolResult.
 *
 * <p>支持:
 * <ul>
 *   <li>结构化输出（structuredContent）</li>
 *   <li>校验失败信息（validationResult）</li>
 *   <li>工具产生的附加消息（newMessages）</li>
 *   <li>Builder 模式构建</li>
 * </ul>
 * @author lanxia39@163.com
 */
public final class ToolResult {
    private final String toolName;
    private final Object output;
    private final Object structuredContent;
    private final String error;
    private final long durationMs;
    private final ValidationResult validationResult;
    private final List<Map<String, Object>> newMessages;

    private ToolResult(Builder builder) {
        this.toolName = builder.toolName;
        this.output = builder.output;
        this.structuredContent = builder.structuredContent;
        this.error = builder.error;
        this.durationMs = builder.durationMs;
        this.validationResult = builder.validationResult;
        this.newMessages = Collections.unmodifiableList(builder.newMessages);
    }

    /** @deprecated 使用 Builder 模式替代. */
    @Deprecated
    public ToolResult(String toolName, Object output, String error, long durationMs) {
        this.toolName = toolName;
        this.output = output;
        this.structuredContent = null;
        this.error = error;
        this.durationMs = durationMs;
        this.validationResult = null;
        this.newMessages = Collections.emptyList();
    }

    public String toolName() { return toolName; }
    public Object output() { return output; }
    public Object structuredContent() { return structuredContent; }
    public String error() { return error; }
    public long durationMs() { return durationMs; }
    public ValidationResult validationResult() { return validationResult; }
    public List<Map<String, Object>> newMessages() { return newMessages; }
    public boolean isError() { return error != null && !error.isEmpty(); }
    public boolean isValidated() { return validationResult != null; }

    /** @deprecated 使用 Builder.success() 替代. */
    @Deprecated
    public static ToolResult success(String toolName, Object output) {
        return new ToolResult(toolName, output, null, 0);
    }

    /** @deprecated 使用 Builder.failure() 替代. */
    @Deprecated
    public static ToolResult failure(String toolName, String error) {
        return new ToolResult(toolName, null, error, 0);
    }

    // ---- Builder ----

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String toolName;
        private Object output;
        private Object structuredContent;
        private String error;
        private long durationMs;
        private ValidationResult validationResult;
        private List<Map<String, Object>> newMessages = Collections.emptyList();

        public Builder toolName(String v) { toolName = v; return this; }
        public Builder output(Object v) { output = v; return this; }
        public Builder structuredContent(Object v) { structuredContent = v; return this; }
        public Builder error(String v) { error = v; return this; }
        public Builder durationMs(long v) { durationMs = v; return this; }
        public Builder validationResult(ValidationResult v) { validationResult = v; return this; }
        public Builder newMessages(List<Map<String, Object>> v) { newMessages = v; return this; }

        public static ToolResult success(String toolName, Object output, long durationMs) {
            return new Builder().toolName(toolName).output(output).durationMs(durationMs).build();
        }

        public static ToolResult failure(String toolName, String error, ValidationResult vr) {
            return new Builder().toolName(toolName).error(error).validationResult(vr).build();
        }

        public ToolResult build() { return new ToolResult(this); }
    }
}
