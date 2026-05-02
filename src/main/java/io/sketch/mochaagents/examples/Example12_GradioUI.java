package io.sketch.mochaagents.examples;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import io.sketch.mochaagents.agent.impl.CodeAgent;
import io.sketch.mochaagents.tool.ToolRegistry;
import io.sketch.mochaagents.examples.tools.WebSearchTool;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Example12 — 对应 smolagents 的 gradio_ui.py.
 *
 * <p>演示 GradioUI 风格的交互式 Web 聊天界面，使用 JDK 内置 HttpServer.
 *
 * <pre>
 *   smolagents 对应:
 *     agent = CodeAgent(tools=[WebSearchTool()], model=model)
 *     GradioUI(agent, file_upload_folder="./data").launch()
 * </pre>
 */
public final class Example12_GradioUI {

    private static final String HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>MochaAgents Chat</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            max-width: 900px; margin: 0 auto; padding: 20px;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
        }
        .container {
            background: white; border-radius: 16px; padding: 30px;
            box-shadow: 0 10px 40px rgba(0,0,0,0.15);
        }
        h1 { color: #333; text-align: center; margin-bottom: 10px; font-size: 28px; }
        .subtitle { text-align: center; color: #888; margin-bottom: 25px; font-size: 14px; }
        .chat-container {
            border: 1px solid #e0e0e0; border-radius: 12px; height: 420px;
            overflow-y: auto; padding: 15px; margin-bottom: 20px;
            background: #fafafa;
        }
        .message { margin-bottom: 12px; padding: 10px 14px; border-radius: 10px;
            max-width: 85%; line-height: 1.5; font-size: 15px; }
        .user-message { background: #667eea; color: white; margin-left: auto; text-align: right; }
        .agent-message { background: #f0f0f0; color: #333; }
        .loading { color: #999; font-style: italic; }
        .input-container { display: flex; gap: 10px; }
        input[type="text"] {
            flex: 1; padding: 14px; border: 2px solid #e0e0e0; border-radius: 10px;
            font-size: 16px; outline: none; transition: border-color 0.2s;
        }
        input[type="text"]:focus { border-color: #667eea; }
        button {
            padding: 14px 28px; background: #667eea; color: white; border: none;
            border-radius: 10px; cursor: pointer; font-size: 16px; font-weight: 600;
            transition: background 0.2s; white-space: nowrap;
        }
        button:hover { background: #5a6fd6; }
        button:disabled { background: #ccc; cursor: not-allowed; }
    </style>
</head>
<body>
    <div class="container">
        <h1>🤖 MochaAgents Chat</h1>
        <div class="subtitle">CodeAgent with WebSearch — Gradio-UI style</div>
        <div class="chat-container" id="chat-container">
            <div class="message agent-message">
                Hello! I'm a CodeAgent with web search capability. Ask me anything!
            </div>
        </div>
        <div class="input-container">
            <input type="text" id="message-input" placeholder="Type your question here..." autofocus>
            <button onclick="sendMessage()" id="send-button">Send</button>
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
            sendButton.disabled = true;
            sendButton.textContent = 'Thinking...';

            try {
                const resp = await fetch('/chat', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({message: msg}),
                });
                const data = await resp.json();
                addMessage(data.reply, false);
            } catch (e) {
                addMessage('Error: ' + e.message, false);
            } finally {
                sendButton.disabled = false;
                sendButton.textContent = 'Send';
                messageInput.focus();
            }
        }

        messageInput.addEventListener('keypress', e => {
            if (e.key === 'Enter') sendMessage();
        });
    </script>
</body>
</html>""";

    public static void main(String[] args) throws IOException {
        System.out.println("=".repeat(60));
        System.out.println("Example12: GradioUI — 交互式 Web 聊天界面");
        System.out.println("=".repeat(60));

        // 创建 CodeAgent（带 WebSearchTool）
        var registry = new ToolRegistry();
        registry.register(new WebSearchTool());

        var agent = CodeAgent.builder()
                .name("gradio_agent")
                .description("Example agent with web search")
                .llm(LLMFactory.create())
                .toolRegistry(registry)
                .maxSteps(3)
                .build();

        // 启动嵌入式 HTTP 服务器
        int port = 8088;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // GET / → HTML 页面
        server.createContext("/", exchange -> {
            byte[] html = HTML.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html);
            }
        });

        // POST /chat → Agent 响应
        server.createContext("/chat", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"reply\":\"Method not allowed\"}");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String message = extractJsonField(body, "message");
            System.out.println("[Chat] User: " + message);

            String reply;
            try {
                reply = agent.run(message);
                System.out.println("[Chat] Agent: " + reply);
            } catch (Exception e) {
                reply = "Error: " + e.getMessage();
            }
            sendJson(exchange, 200, "{\"reply\":" + escapeJson(reply) + "}");
        });

        server.setExecutor(null); // use default executor
        server.start();

        System.out.println("\n🌐 Chat server started at http://localhost:" + port + "/");
        System.out.println("   Open your browser and start chatting with the agent!");
        System.out.println("   Press Ctrl+C to stop.\n");

        // 保持运行
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down server...");
            server.stop(0);
        }));

        // 5 秒后自动关闭（演示模式）
        System.out.println("Server will auto-stop after 60 seconds for demo purposes.");
        System.out.println("=".repeat(60));

        try {
            Thread.sleep(60_000);
        } catch (InterruptedException ignored) {}

        server.stop(0);
        System.out.println("Example12 Complete.");
    }

    private static void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int start = json.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        int colon = json.indexOf(':', start);
        if (colon < 0) return "";
        int valStart = colon + 1;
        while (valStart < json.length() && (json.charAt(valStart) == ' ' || json.charAt(valStart) == '\"')) {
            valStart++;
        }
        int valEnd = json.indexOf('\"', valStart);
        if (valEnd < 0) {
            valEnd = json.indexOf('}', valStart);
            if (valEnd < 0) valEnd = json.length();
        }
        return json.substring(valStart, valEnd).trim();
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    private Example12_GradioUI() {}
}
