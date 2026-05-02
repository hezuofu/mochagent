package io.sketch.mochaagents.tool;

import java.util.Map;

/**
 * 工具结果.
 */
public final class ToolResult {
    private final String toolName;
    private final Object output;
    private final String error;
    private final long durationMs;

    public ToolResult(String toolName, Object output, String error, long durationMs) {
        this.toolName = toolName; this.output = output; this.error = error; this.durationMs = durationMs;
    }
    public String toolName() { return toolName; }
    public Object output() { return output; }
    public String error() { return error; }
    public long durationMs() { return durationMs; }
    public boolean isError() { return error != null && !error.isEmpty(); }

    public static ToolResult success(String toolName, Object output) { return new ToolResult(toolName, output, null, 0); }
    public static ToolResult failure(String toolName, String error) { return new ToolResult(toolName, null, error, 0); }
}
