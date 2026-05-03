package io.sketch.mochaagents.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

/**
 * MCP client over stdio — connects to external MCP servers, discovers their tools,
 * and wraps them as local Tool objects callable from the agent.
 * @author lanxia39@163.com
 */
public class StdioMcpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(StdioMcpClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private Process process;
    private PrintWriter out;
    private BufferedReader in;
    private boolean connected;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    @Override
    public void connect(String serverCommand) {
        try {
            ProcessBuilder pb = new ProcessBuilder(serverCommand.split("\\s+"));
            pb.redirectErrorStream(false);
            process = pb.start();
            out = new PrintWriter(process.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(process.getInputStream()));

            // Initialize handshake
            ObjectNode init = JSON.createObjectNode();
            init.put("jsonrpc", "2.0");
            init.put("id", 1);
            init.put("method", "initialize");
            init.putObject("params").putObject("capabilities").put("roots", "listChanged");

            send(init);
            JsonNode resp = readResponse();
            if (resp != null && resp.has("result")) {
                connected = true;
                log.info("MCP client connected to: {}", serverCommand);
            }

            // Send initialized notification
            ObjectNode notified = JSON.createObjectNode();
            notified.put("jsonrpc", "2.0");
            notified.put("method", "notifications/initialized");
            send(notified);

        } catch (IOException e) {
            log.error("MCP client connection failed: {}", e.getMessage());
            connected = false;
        }
    }

    @Override
    public void disconnect() {
        connected = false;
        if (process != null) process.destroy();
        tools.clear();
        log.info("MCP client disconnected");
    }

    @Override
    public boolean isConnected() { return connected; }

    @Override
    public List<Tool> discoverTools() {
        if (!connected) return List.of();

        ObjectNode req = JSON.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 2);
        req.put("method", "tools/list");

        send(req);
        JsonNode resp = readResponse();
        if (resp == null || !resp.has("result")) return List.of();

        JsonNode toolsNode = resp.get("result").get("tools");
        if (toolsNode == null || !toolsNode.isArray()) return List.of();

        List<Tool> discovered = new ArrayList<>();
        for (JsonNode tn : (ArrayNode) toolsNode) {
            String name = tn.has("name") ? tn.get("name").asText() : "";
            String desc = tn.has("description") ? tn.get("description").asText() : "";
            if (name.isEmpty()) continue;

            McpTool tool = new McpTool(name, desc, this);
            tools.put(name, tool);
            discovered.add(tool);
        }
        log.info("MCP discovered {} tools", discovered.size());
        return discovered;
    }

    @Override
    public ToolResult invokeTool(String toolName, Map<String, Object> arguments) {
        ObjectNode req = JSON.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", System.currentTimeMillis());
        req.put("method", "tools/call");
        ObjectNode params = req.putObject("params");
        params.put("name", toolName);

        ObjectNode args = params.putObject("arguments");
        arguments.forEach((k, v) -> args.put(k, String.valueOf(v)));

        send(req);
        JsonNode resp = readResponse();

        if (resp != null && resp.has("result")) {
            JsonNode content = resp.get("result").get("content");
            String text = content != null && content.isArray() && !content.isEmpty()
                    ? content.get(0).get("text").asText() : resp.get("result").toString();
            return ToolResult.success(toolName, text);
        }

        String err = resp != null && resp.has("error")
                ? resp.get("error").get("message").asText() : "Unknown MCP error";
        return ToolResult.failure(toolName, err);
    }

    private void send(ObjectNode msg) {
        try { out.println(JSON.writeValueAsString(msg)); out.flush(); }
        catch (Exception e) { log.error("MCP send error: {}", e.getMessage()); }
    }

    private JsonNode readResponse() {
        try {
            String line = in.readLine();
            return line != null ? JSON.readTree(line) : null;
        } catch (IOException e) {
            log.error("MCP read error: {}", e.getMessage());
            return null;
        }
    }

    /** Wraps a single remote MCP tool as a local Tool. */
    private record McpTool(String name, String description, StdioMcpClient client) implements Tool {
        @Override public String getName() { return name; }
        @Override public String getDescription() { return description; }
        @Override public Map<String, ToolInput> getInputs() { return Map.of(); }
        @Override public String getOutputType() { return "string"; }
        @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.MEDIUM; }
        @Override public Object call(Map<String, Object> args) {
            ToolResult r = client.invokeTool(name, args);
            return r.isError() ? "MCP Error: " + r.error() : r.output();
        }
    }
}
