package io.sketch.mochaagents.tool.workbench;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolResult;
import java.util.List;
import java.util.Map;

/**
 * 工作台接口 — 多工具协同工作空间，支持工具组合与管道执行.
 */
public interface Workbench {

    /** 注册工具到工作台 */
    void addTool(Tool tool);

    /** 移除工具 */
    void removeTool(String toolName);

    /** 获取已注册工具列表 */
    List<Tool> getTools();

    /** 执行单个工具调用 */
    ToolResult execute(String toolName, Map<String, Object> arguments);

    /** 创建工具管道 */
    ToolPipeline pipeline(String... toolNames);

    /** 获取工具编排器 */
    ToolOrchestrator orchestrator();

    /** 关闭工作台，释放资源 */
    void close();
}
