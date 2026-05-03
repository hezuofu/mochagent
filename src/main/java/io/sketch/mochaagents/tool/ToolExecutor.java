package io.sketch.mochaagents.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 工具执行器 — 权限校验 + 执行 + 结果封装.
 * @author lanxia39@163.com
 */
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry registry;

    public ToolExecutor(ToolRegistry registry) { this.registry = registry; }

    public ToolResult execute(String toolName, Map<String, Object> arguments) {
        Tool tool = registry.get(toolName);
        if (tool == null) {
            log.warn("Tool '{}' not found in registry", toolName);
            return ToolResult.Builder.failure(toolName, "Tool not found: " + toolName, null);
        }
        try {
            long start = System.currentTimeMillis();
            Object result = tool.call(arguments);
            long elapsed = System.currentTimeMillis() - start;
            log.info("[Tool] {} ({}ms) args={}", toolName, elapsed, summarizeArgs(arguments));
            return ToolResult.Builder.success(toolName, result, elapsed);
        } catch (Exception e) {
            log.error("Tool '{}' execution failed", toolName, e);
            return ToolResult.Builder.failure(toolName, e.getMessage(), null);
        }
    }

    private static String summarizeArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        args.forEach((k, v) -> {
            String val = String.valueOf(v);
            if (val.length() > 80) val = val.substring(0, 80) + "...";
            sb.append(k).append("=").append(val).append(", ");
        });
        sb.setLength(sb.length() - 2);
        sb.append("}");
        return sb.toString();
    }
}
