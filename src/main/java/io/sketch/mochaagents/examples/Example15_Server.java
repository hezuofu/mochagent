package io.sketch.mochaagents.examples;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import io.sketch.mochaagents.core.impl.CodeAgent;
import io.sketch.mochaagents.llm.provider.MockLLM;
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolRegistry;
import io.sketch.mochaagents.examples.tools.WebSearchTool;
import io.sketch.mochaagents.examples.tools.WeatherTool;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example15 — 对应 smolagents 的 server/main.py.
 *
 * <p>演示 Starlette 风格的 Agent HTTP 服务器:
 * <ul>
 *   <li>路由注册 (Route-based routing)</li>
 *   <li>MCP Client 工具集成 (Model Context Protocol)</li>
 *   <li>请求统计与监控</li>
 *   <li>优雅 shutdown</li>
 * </ul>
 *
 * <pre>
 *   smolagents 对应:
 *     mcp_client = MCPClient(server_parameters=mcp_server_parameters)
 *     agent = CodeAgent(model=model, tools=mcp_client.get_tools())
 *     app = Starlette(routes=[Route("/", homepage), Route("/chat", chat, methods=["POST"])])
 *     app.on_shutdown = [shutdown]
 * </pre>
 */
public final class Example15_Server {

    // ─── MCP Client 模拟 ───

    /**
     * 模拟 MCP (Model Context Protocol) 客户端.
     * 从远程 MCP Server 获取工具列表.
     */
    static final class McpClient {
        private final String url;
        private final String transport;
        private boolean connected = true;

        McpClient(String url, String transport) {
            this.url = url;
            this.transport = transport;
        }

        /** 从 MCP Server 获取工具列表 */
        List<Tool> getTools() {
            System.out.println("  [MCP] Fetching tools from " + url + " (transport: " + transport + ")");
            return List.of(
                    new WebSearchTool(),
                    new WeatherTool()
            );
        }

        void disconnect() {
            connected = false;
            System.out.println("  [MCP] Disconnected from " + url);
        }

        boolean isConnected() { return connected; }
    }

    // ─── 请求统计 ───

    static final class ServerStats {
        private final AtomicInteger totalRequests = new AtomicInteger();
        private final AtomicInteger chatRequests = new AtomicInteger();
        private final Map<String, AtomicInteger> endpoints = new ConcurrentHashMap<>();
        private final long startTime = System.currentTimeMillis();

        void record(String endpoint) {
            totalRequests.incrementAndGet();
            endpoints.computeIfAbsent(endpoint, k -> new AtomicInteger()).incrementAndGet();
            if ("/chat".equals(endpoint)) chatRequests.incrementAndGet();
        }

        void printStats() {
            long uptime = (System.currentTimeMillis() - startTime) / 1000;
            System.out.println("\n┌─ Server Stats ──────────────────────────────────────┐");
            System.out.printf("│  Uptime           : %d seconds%n", uptime);
            System.out.printf("│  Total Requests   : %d%n", totalRequests.get());
            System.out.printf("│  Chat Requests    : %d%n", chatRequests.get());
            for (var entry : endpoints.entrySet()) {
                System.out.printf("│  %-16s : %d%n", entry.getKey(), entry.getValue().get());
            }
            System.out.println("└──────────────────────────────────────────────────────┘");
        }
    }

    // ─── HTTP 工具方法 ───

    private static void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int start = json.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        int colon = json.indexOf(':', start);
        if (colon < 0) return "";
        int valStart = colon + 1;
        while (valStart < json.length() &&
                (json.charAt(valStart) == ' ' || json.charAt(valStart) == '\"')) valStart++;
        int valEnd = json.indexOf('\"', valStart);
        if (valEnd < 0) valEnd = json.indexOf('}', valStart);
        if (valEnd < 0) valEnd = json.length();
        return json.substring(valStart, valEnd).trim();
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    // ─── HTML 页面 (Starlette 风格) ───

    private static final String HOME_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>MochaAgents Server</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, sans-serif; max-width: 800px; margin: 0 auto;
               padding: 20px; background: #f5f5f5; }
        .container { background: white; border-radius: 12px; padding: 30px;
                     box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
        h1 { color: #333; text-align: center; margin-bottom: 30px; }
        .chat-container { border: 1px solid #ddd; border-radius: 8px; height: 400px;
                          overflow-y: auto; padding: 15px; margin-bottom: 20px;
                          background: #fafafa; }
        .message { margin-bottom: 15px; padding: 10px; border-radius: 6px; }
        .user-message { background: #007bff; color: white; margin-left: 50px; }
        .agent-message { background: #e9ecef; color: #333; margin-right: 50px; }
        .input-container { display: flex; gap: 10px; }
        input[type="text"] { flex: 1; padding: 12px; border: 1px solid #ddd;
                             border-radius: 6px; font-size: 16px; }
        button { padding: 12px 24px; background: #007bff; color: white; border: none;
                 border-radius: 6px; cursor: pointer; font-size: 16px; }
        button:hover { background: #0056b3; }
        button:disabled { background: #ccc; cursor: not-allowed; }
        .endpoints { margin-top: 20px; padding: 15px; background: #f8f9fa;
                     border-radius: 8px; font-size: 14px; }
        .endpoints code { background: #e9ecef; padding: 2px 6px; border-radius: 4px; }
    </style>
</head>
<body>
    <div class="container">
        <h1>🤖 MochaAgents Server</h1>
        <div class="chat-container" id="chat-container">
            <div class="message agent-message">
                Hello! I'm a code agent with MCP tools. Ask me anything!
            </div>
        </div>
        <div class="input-container">
            <input type="text" id="message-input" placeholder="Ask me anything..." autofocus>
            <button onclick="sendMessage()" id="send-button">Send</button>
        </div>
        <div class="endpoints">
            <strong>API Endpoints:</strong><br>
            <code>GET  /</code> — This page<br>
            <code>POST /chat</code> — Send a message to the agent<br>
            <code>GET  /health</code> — Health check<br>
            <code>GET  /stats</code> — Server statistics
        </div>
    </div>
    <script>
        const chatContainer = document.getElementById('chat-container');
        const messageInput = document.getElementById('message-input');
        const sendButton = document.getElementById('send-button');
        function addMessage(content, isUser) {
            const div = document.createElement('div');
            div.className = 'message ' + (isUser ? 'user-message' : 'agent-message');
            div.textContent = content;
            chatContainer.appendChild(div);
            chatContainer.scrollTop = chatContainer.scrollHeight;
        }
        async function sendMessage() {
            const msg = messageInput.value.trim();
            if (!msg) return;
            addMessage(msg, true);
            messageInput.value = '';
            sendButton.disabled = true; sendButton.textContent = 'Sending...';
            const loading = document.createElement('div');
            loading.className = 'message agent-message'; loading.textContent = 'Thinking...';
            chatContainer.appendChild(loading);
            try {
                const resp = await fetch('/chat', {
                    method: 'POST', headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({message: msg})
                });
                const data = await resp.json();
                chatContainer.removeChild(loading);
                addMessage(data.reply, false);
            } catch(e) {
                chatContainer.removeChild(loading);
                addMessage('Error: ' + e.message, false);
            } finally {
                sendButton.disabled = false; sendButton.textContent = 'Send';
                messageInput.focus();
            }
        }
        messageInput.addEventListener('keypress', e => {
            if (e.key === 'Enter') sendMessage();
        });
    </script>
</body>
</html>""";

    // ─── main ───

    public static void main(String[] args) throws IOException {
        System.out.println("=".repeat(60));
        System.out.println("Example15: Server — Starlette 风格 HTTP Agent 服务器");
        System.out.println("=".repeat(60));

        // ── MCP 客户端 ──
        System.out.println("\n[1] Initializing MCP client...");
        var mcpClient = new McpClient(
                "https://evalstate-hf-mcp-server.hf.space/mcp",
                "streamable-http");

        // ── 创建 Agent（使用 MCP 工具） ──
        var registry = new ToolRegistry();
        List<Tool> mcpTools = mcpClient.getTools();
        for (Tool tool : mcpTools) {
            registry.register(tool);
        }
        System.out.println("    ✓ " + mcpTools.size() + " tools loaded from MCP server");

        var agent = CodeAgent.builder()
                .name("server-agent")
                .description("Agent serving via HTTP with MCP tools")
                .llm(LLMFactory.create())
                .toolRegistry(registry)
                .maxSteps(3)
                .build();

        // ── 启动 HTTP 服务器 ──
        int port = 8089;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        var stats = new ServerStats();

        // 路由: GET / → 首页
        server.createContext("/", exchange -> {
            stats.record("/");
            byte[] html = HOME_HTML.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html);
            }
        });

        // 路由: POST /chat → Agent 对话
        server.createContext("/chat", exchange -> {
            stats.record("/chat");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            String body = readBody(exchange);
            String message = extractJsonField(body, "message");
            System.out.println("[Server] Chat: " + message);

            try {
                String reply = agent.run(message);
                sendJson(exchange, 200, "{\"reply\":" + escapeJson(reply) + "}");
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"error\":" + escapeJson(e.getMessage()) + "}");
            }
        });

        // 路由: GET /health → 健康检查
        server.createContext("/health", exchange -> {
            stats.record("/health");
            boolean mcpOk = mcpClient.isConnected();
            String health = String.format(
                    "{\"status\":\"ok\",\"mcp_connected\":%b,\"agent\":\"server-agent\"}", mcpOk);
            sendJson(exchange, 200, health);
        });

        // 路由: GET /stats → 统计
        server.createContext("/stats", exchange -> {
            stats.record("/stats");
            long uptime = (System.currentTimeMillis() - stats.startTime) / 1000;
            String statJson = String.format(
                    "{\"uptime_seconds\":%d,\"total_requests\":%d,\"chat_requests\":%d}",
                    uptime, stats.totalRequests.get(), stats.chatRequests.get());
            sendJson(exchange, 200, statJson);
        });

        server.setExecutor(null);
        server.start();

        System.out.println("\n[2] Server started!");
        System.out.println("    🌐 http://localhost:" + port + "/");
        System.out.println("    Endpoints:");
        System.out.println("      GET  /        — Chat UI");
        System.out.println("      POST /chat    — Agent chat API");
        System.out.println("      GET  /health  — Health check");
        System.out.println("      GET  /stats   — Statistics");

        // ── Shutdown Hook (模拟 on_shutdown) ──
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[Shutdown] Executing on_shutdown handlers...");
            mcpClient.disconnect();
            server.stop(0);
            stats.printStats();
            System.out.println("[Shutdown] Complete.");
        }));

        // ── 模拟请求（演示模式） ──
        System.out.println("\n[3] Simulating requests for demo...");
        try {
            // 模拟几个请求
            simulateRequest(stats, agent, "/chat",
                    "{\"message\":\"What's the capital of France?\"}");
            Thread.sleep(500);
            simulateRequest(stats, agent, "/chat",
                    "{\"message\":\"What's the weather like?\"}");
            Thread.sleep(500);
            simulateRequest(stats, agent, "/health", "");
        } catch (InterruptedException ignored) {}

        // 打印统计
        stats.printStats();

        System.out.println("\n[4] Server running. Press Ctrl+C to stop.");
        System.out.println("    Auto-stopping in 30 seconds for demo...");

        try {
            Thread.sleep(30_000);
        } catch (InterruptedException ignored) {}

        mcpClient.disconnect();
        server.stop(0);
        stats.printStats();
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Example15 Complete.");
    }

    private static void simulateRequest(ServerStats stats, CodeAgent agent,
                                         String endpoint, String body) {
        stats.record(endpoint);
        if (!body.isEmpty()) {
            String msg = extractJsonField(body, "message");
            System.out.println("  [Simulated] " + endpoint + " → \"" + msg + "\"");
            try {
                String reply = agent.run(msg);
                System.out.println("  [Simulated] Reply: " +
                        reply.substring(0, Math.min(60, reply.length())) + "...");
            } catch (Exception e) {
                System.out.println("  [Simulated] Error: " + e.getMessage());
            }
        }
    }

    private Example15_Server() {}
}
