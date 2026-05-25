// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Low-level LSP client — JSON-RPC 2.0 over stdio.
 *
 * @author lanxia39@163.com
 */
public class LspClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LspClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AtomicInteger idCounter = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, Consumer<JsonNode>> notifications = new ConcurrentHashMap<>();

    private Process process;
    private BufferedWriter writer;
    private volatile boolean running;

    public void start(String command, String... args) throws IOException {
        ProcessBuilder pb = new ProcessBuilder();
        pb.command(merge(command, args));
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        process = pb.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        running = true;

        Thread reader = new Thread(() -> readLoop(process.getInputStream()));
        reader.setDaemon(true);
        reader.start();
    }

    public CompletableFuture<JsonNode> sendRequest(String method, JsonNode params) {
        int id = idCounter.getAndIncrement();
        ObjectNode msg = JSON.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        msg.set("params", params);

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        String raw = msg.toString();
        synchronized (writer) {
            try {
                writer.write("Content-Length: " + raw.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + raw);
                writer.flush();
            } catch (IOException e) {
                running = false;
                future.completeExceptionally(e);
                pending.remove(id);
            }
        }
        return future;
    }

    public void sendNotification(String method, JsonNode params) {
        ObjectNode msg = JSON.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        msg.set("params", params);
        sendRaw(msg.toString());
    }

    public void onNotification(String method, Consumer<JsonNode> handler) {
        notifications.put(method, handler);
    }

    public boolean isRunning() { return running && process != null && process.isAlive(); }

    @Override
    public void close() {
        running = false;
        try { sendRequest("shutdown", JSON.createObjectNode()).get(3, TimeUnit.SECONDS); }
        catch (Exception ignored) {}
        sendNotification("exit", JSON.createObjectNode());
        try { process.waitFor(3, TimeUnit.SECONDS); }
        catch (Exception ignored) {}
        process.destroyForcibly();
        pending.values().forEach(f -> f.cancel(true));
    }

    // ── Private ──

    private void readLoop(InputStream in) {
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            while (running) {
                String header = reader.readLine();
                if (header == null) break;
                int contentLength = 0;
                while (header != null && !header.isEmpty()) {
                    if (header.startsWith("Content-Length:"))
                        contentLength = Integer.parseInt(header.substring(15).trim());
                    header = reader.readLine();
                }
                char[] buf = new char[contentLength];
                int read = 0;
                while (read < contentLength) read += reader.read(buf, read, contentLength - read);
                JsonNode msg = JSON.readTree(new String(buf));
                if (msg.has("id")) {
                    int id = msg.get("id").asInt();
                    CompletableFuture<JsonNode> f = pending.remove(id);
                    if (f != null) {
                        if (msg.has("error")) f.completeExceptionally(new LspException(msg.get("error").get("message").asText()));
                        else f.complete(msg.get("result"));
                    }
                } else if (msg.has("method")) {
                    Consumer<JsonNode> h = notifications.get(msg.get("method").asText());
                    if (h != null) h.accept(msg.get("params"));
                }
            }
        } catch (Exception e) {
            if (running) log.warn("LSP read loop error: {}", e.getMessage());
        }
    }

    private void sendRaw(String raw) {
        if (!running) return;
        synchronized (writer) {
            try {
                writer.write("Content-Length: " + raw.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + raw);
                writer.flush();
            } catch (IOException e) { running = false; }
        }
    }

    private static String[] merge(String command, String... args) {
        String[] result = new String[args.length + 1];
        result[0] = command;
        System.arraycopy(args, 0, result, 1, args.length);
        return result;
    }

    public static final class LspException extends RuntimeException {
        public LspException(String msg) { super(msg); }
    }
}
