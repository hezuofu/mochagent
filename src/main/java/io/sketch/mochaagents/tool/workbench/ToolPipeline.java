package io.sketch.mochaagents.tool.workbench;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 工具管道 — 链式工具调用，前一个工具的输出可作为下一个工具的输入.
 */
public class ToolPipeline {

    private final Map<String, Tool> tools;
    private final List<PipelineStage> stages = new ArrayList<>();
    private Function<Object, Object> resultTransformer = Function.identity();

    ToolPipeline(Map<String, Tool> tools) {
        this.tools = tools;
    }

    /** 添加管道阶段 */
    public ToolPipeline then(String toolName, Function<Object, Map<String, Object>> argumentMapper) {
        stages.add(new PipelineStage(toolName, argumentMapper));
        return this;
    }

    /** 添加管道阶段，使用默认参数映射 */
    public ToolPipeline then(String toolName) {
        return then(toolName, prev -> Map.of("input", prev));
    }

    /** 设置结果转换器 */
    public ToolPipeline transform(Function<Object, Object> transformer) {
        this.resultTransformer = transformer;
        return this;
    }

    /** 执行管道 */
    public List<ToolResult> execute(Object initialInput) {
        List<ToolResult> results = new ArrayList<>();
        Object currentInput = initialInput;

        for (PipelineStage stage : stages) {
            Tool tool = tools.get(stage.toolName);
            if (tool == null) {
                results.add(ToolResult.failure(stage.toolName, "Tool not found"));
                return results;
            }
            try {
                Map<String, Object> args = stage.argumentMapper.apply(currentInput);
                Object output = tool.call(args);
                ToolResult result = new ToolResult(stage.toolName, output, null, 0);
                results.add(result);
                if (result.isError()) return results;
                currentInput = result.output();
            } catch (Exception e) {
                results.add(ToolResult.failure(stage.toolName, e.getMessage()));
                return results;
            }
        }
        return results;
    }

    /** 获取最终转换结果 */
    public Object getFinalResult(List<ToolResult> results) {
        if (results.isEmpty()) return null;
        ToolResult last = results.get(results.size() - 1);
        return resultTransformer.apply(last.output());
    }

    private record PipelineStage(String toolName, Function<Object, Map<String, Object>> argumentMapper) {}
}
