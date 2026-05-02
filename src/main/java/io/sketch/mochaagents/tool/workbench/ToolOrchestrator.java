package io.sketch.mochaagents.tool.workbench;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 工具编排器 — 智能编排多工具调用，支持顺序/并行/条件执行策略.
 */
public class ToolOrchestrator {

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final List<Function<Map<String, Object>, ToolResult>> preHooks = new ArrayList<>();
    private final List<Function<ToolResult, ToolResult>> postHooks = new ArrayList<>();

    public ToolOrchestrator register(Tool tool) {
        tools.put(tool.getName(), tool);
        return this;
    }

    /** 执行前钩子 */
    public ToolOrchestrator before(Function<Map<String, Object>, ToolResult> hook) {
        preHooks.add(hook);
        return this;
    }

    /** 执行后钩子 */
    public ToolOrchestrator after(Function<ToolResult, ToolResult> hook) {
        postHooks.add(hook);
        return this;
    }

    /** 顺序执行多个工具 */
    public List<ToolResult> executeSequential(List<ToolCall> calls) {
        List<ToolResult> results = new ArrayList<>();
        for (ToolCall call : calls) {
            Tool tool = tools.get(call.toolName);
            if (tool == null) {
                results.add(ToolResult.failure(call.toolName, "Tool not found: " + call.toolName));
                continue;
            }
            try {
                Object output = tool.call(call.arguments);
                ToolResult result = new ToolResult(call.toolName, output, null, 0);
                for (Function<ToolResult, ToolResult> hook : postHooks) {
                    result = hook.apply(result);
                }
                results.add(result);
            } catch (Exception e) {
                results.add(ToolResult.failure(call.toolName, e.getMessage()));
            }
        }
        return results;
    }

    /** 顺序执行，任一失败则终止 */
    public List<ToolResult> executeFailFast(List<ToolCall> calls) {
        List<ToolResult> results = new ArrayList<>();
        for (ToolCall call : calls) {
            Tool tool = tools.get(call.toolName);
            if (tool == null) {
                results.add(ToolResult.failure(call.toolName, "Tool not found"));
                break;
            }
            try {
                Object output = tool.call(call.arguments);
                ToolResult result = new ToolResult(call.toolName, output, null, 0);
                results.add(result);
                if (result.isError()) break;
            } catch (Exception e) {
                results.add(ToolResult.failure(call.toolName, e.getMessage()));
                break;
            }
        }
        return results;
    }

    /** 工具调用描述 */
    public record ToolCall(String toolName, Map<String, Object> arguments) {
        public static ToolCall of(String toolName, Map<String, Object> args) {
            return new ToolCall(toolName, args);
        }
    }
}
