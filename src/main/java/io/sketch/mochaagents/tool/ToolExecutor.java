package io.sketch.mochaagents.tool;

import java.util.Map;

/**
 * 工具执行器 — 权限校验 + 执行 + 结果封装.
 */
public class ToolExecutor {
    private final ToolRegistry registry;

    public ToolExecutor(ToolRegistry registry) { this.registry = registry; }

    public ToolResult execute(String toolName, Map<String, Object> arguments) {
        Tool tool = registry.get(toolName);
        if (tool == null) return ToolResult.Builder.failure(toolName, "Tool not found: " + toolName, null);
        try {
            long start = System.currentTimeMillis();
            Object result = tool.call(arguments);
            return ToolResult.Builder.success(toolName, result, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.Builder.failure(toolName, e.getMessage(), null);
        }
    }
}
