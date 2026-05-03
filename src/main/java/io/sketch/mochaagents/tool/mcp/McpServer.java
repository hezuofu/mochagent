package io.sketch.mochaagents.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sketch.mochaagents.agent.impl.ToolCallingAgent;
import io.sketch.mochaagents.AgentBootstrap;
import io.sketch.mochaagents.llm.provider.MockLLM;
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP (Model Context Protocol) Server — JSON-RPC 2.0 over stdio.
 *
 * <p>Implements the MCP specification: initialize, tools/list, tools/call.
 * Clients (Claude Code, Cursor, etc.) connect via stdio.
 * @author lanxia39@163.com
 */
public class McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ToolRegistry toolRegistry;
    private final AgentBootstrap bootstrap;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public McpServer() {
        this.bootstrap = AgentBootstrap.init();
        this.toolRegistry = bootstrap.toolRegistry();
    }

    /** Start the MCP server on stdio (blocking). */
    public void serve() {
        log.info("MCP Server starting on stdio (JSON-RPC 2.0)");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
             PrintWriter out = new PrintWriter(System.out, true)) {

            while (running.get()) {
                String line = in.readLine();
                if (line == null) {
                    log.info("MCP Server: EOF received, shutting down");
                    break;
                }
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    JsonNode request = JSON.readTree(line);
                    String method = request.has("method") ? request.get("method").asText() : "";
                    int id = request.has("id") ? request.get("id").asInt() : -1;

                    JsonNode response = handleMethod(method, request);
                    if (response != null) {
                        out.println(JSON.writeValueAsString(response));
                        out.flush();
                    }
                } catch (Exception e) {
                    log.error("Error processing MCP request: {}", e.getMessage());
                    ObjectNode errorResp = JSON.createObjectNode();
                    errorResp.put("jsonrpc", "2.0");
                    errorResp.put("id", -1);
                    ObjectNode err = errorResp.putObject("error");
                    err.put("code", -32603);
                    err.put("message", "Internal error: " + e.getMessage());
                    out.println(JSON.writeValueAsString(errorResp));
                    out.flush();
                }
            }
        } catch (IOException e) {
            log.error("MCP Server I/O error", e);
        }
    }

    private JsonNode handleMethod(String method, JsonNode request) {
        int id = request.has("id") ? request.get("id").asInt() : 0;

        return switch (method) {
            case "initialize" -> handleInitialize(id, request);
            case "tools/list" -> handleToolsList(id);
            case "tools/call" -> handleToolsCall(id, request);
            case "notifications/initialized" -> null; // no response for notifications
            default -> {
                ObjectNode err = JSON.createObjectNode();
                err.put("jsonrpc", "2.0");
                err.put("id", id);
                ObjectNode e = err.putObject("error");
                e.put("code", -32601);
                e.put("message", "Method not found: " + method);
                yield err;
            }
        };
    }

    private ObjectNode handleInitialize(int id, JsonNode request) {
        log.info("MCP: initialize (client={})",
                request.has("params") ? request.get("params").get("clientInfo") : "unknown");

        ObjectNode resp = JSON.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);

        ObjectNode result = resp.putObject("result");
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("serverName", "MochaAgent MCP Server");
        result.put("serverVersion", "0.1.0");

        ObjectNode caps = result.putObject("capabilities");
        caps.putObject("tools").put("listChanged", false);
        return resp;
    }

    private ObjectNode handleToolsList(int id) {
        ObjectNode resp = JSON.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);

        ObjectNode result = resp.putObject("result");
        ArrayNode tools = result.putArray("tools");

        for (Tool t : toolRegistry.all()) {
            ObjectNode tool = tools.addObject();
            tool.put("name", t.getName());
            tool.put("description", t.getDescription());

            ObjectNode schema = tool.putObject("inputSchema");
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");
            ArrayNode required = schema.putArray("required");

            for (var entry : t.getInputs().entrySet()) {
                ObjectNode prop = props.putObject(entry.getKey());
                prop.put("type", entry.getValue().type());
                prop.put("description", entry.getValue().description());
                if (!entry.getValue().nullable()) {
                    required.add(entry.getKey());
                }
            }
        }

        log.debug("MCP: tools/list returned {} tools", tools.size());
        return resp;
    }

    private ObjectNode handleToolsCall(int id, JsonNode request) {
        JsonNode params = request.get("params");
        String toolName = params != null && params.has("name")
                ? params.get("name").asText() : "";
        JsonNode args = params != null ? params.get("arguments") : null;

        ObjectNode resp = JSON.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);

        if (!toolRegistry.has(toolName)) {
            ObjectNode err = resp.putObject("error");
            err.put("code", -32602);
            err.put("message", "Tool not found: " + toolName);
            return resp;
        }

        try {
            Tool tool = toolRegistry.get(toolName);
            Map<String, Object> argMap = args != null
                    ? JSON.convertValue(args, Map.class) : Map.of();
            Object toolResult = tool.call(argMap);

            ObjectNode result = resp.putObject("result");
            ArrayNode content = result.putArray("content");
            ObjectNode textBlock = content.addObject();
            textBlock.put("type", "text");
            textBlock.put("text", toolResult != null ? toolResult.toString() : "");

            log.debug("MCP: tools/call {} → {}", toolName,
                    toolResult != null ? toolResult.toString().substring(0, Math.min(100, toolResult.toString().length())) : "null");
        } catch (Exception e) {
            log.error("Tool call failed: {} - {}", toolName, e.getMessage());
            ObjectNode err = resp.putObject("error");
            err.put("code", -32000);
            err.put("message", "Tool execution error: " + e.getMessage());
        }

        return resp;
    }

    /** Shutdown the server gracefully. */
    public void shutdown() {
        running.set(false);
        log.info("MCP Server shutdown");
    }
}
