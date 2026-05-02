package io.sketch.mochaagents.tool.mcp;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolResult;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MCP (Model Context Protocol) 客户端 — 连接外部 MCP 服务，将远程工具映射为本地 Tool.
 */
public interface McpClient {

    /** 连接到 MCP 服务器 */
    void connect(String serverUrl);

    /** 断开连接 */
    void disconnect();

    /** 是否已连接 */
    boolean isConnected();

    /** 发现远程可用工具 */
    List<Tool> discoverTools();

    /** 调用远程工具 */
    ToolResult invokeTool(String toolName, Map<String, Object> arguments);
}
