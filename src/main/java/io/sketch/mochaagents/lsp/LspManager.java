// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * LSP manager — routes files to servers by extension, lazy-start, doc sync.
 *
 * <pre>{@code
 * LspManager mgr = new LspManager();
 * mgr.register("typescript", ".ts", new LspServerConfig("typescript-language-server", new String[]{"--stdio"}));
 * mgr.register("python", ".py", new LspServerConfig("pyright-langserver", new String[]{"--stdio"}));
 * // Agent calls:
 * JsonNode defs = mgr.request("src/Foo.ts", "textDocument/definition", params).get();
 * }</pre>
 *
 * @author lanxia39@163.com
 */
public class LspManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LspManager.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    // extension ("ts") → server name
    private final Map<String, String> extensionMap = new ConcurrentHashMap<>();
    private final Map<String, LspServer> servers = new ConcurrentHashMap<>();
    private final Map<String, String> openFiles = new ConcurrentHashMap<>(); // filePath → server name

    public void register(String serverName, String extension, LspServer.LspServerConfig config) {
        String ext = extension.startsWith(".") ? extension.substring(1) : extension;
        extensionMap.put(ext, serverName);
        servers.computeIfAbsent(serverName, k -> new LspServer(k, config));
    }

    public void register(String serverName, List<String> extensions, LspServer.LspServerConfig config) {
        for (String ext : extensions) register(serverName, ext, config);
    }

    public LspServer getServerForFile(String filePath) {
        String ext = extension(filePath);
        if (ext == null) return null;
        String name = extensionMap.get(ext);
        return name != null ? servers.get(name) : null;
    }

    public CompletableFuture<Void> ensureServerStarted(String filePath) {
        LspServer server = getServerForFile(filePath);
        if (server == null) return CompletableFuture.failedFuture(new IllegalStateException("No LSP server for " + filePath));
        if (server.isHealthy()) return CompletableFuture.completedFuture(null);
        return server.start();
    }

    public <T> CompletableFuture<T> request(String filePath, String method, ObjectNode params) {
        LspServer server = getServerForFile(filePath);
        if (server == null)
            return CompletableFuture.failedFuture(new IllegalStateException("No LSP server for " + filePath));
        return ensureOpen(filePath, server).thenCompose(v -> server.sendRequest(method, params));
    }

    public void didOpen(String filePath, String content) {
        LspServer server = getServerForFile(filePath);
        if (server == null || !server.isHealthy()) return;
        ObjectNode params = JSON.createObjectNode();
        params.set("textDocument", textDocumentItem(filePath, content));
        server.sendNotification("textDocument/didOpen", params);
        openFiles.put(filePath, server.name());
    }

    public void didChange(String filePath, String newContent) {
        LspServer server = getServerForFile(filePath);
        if (server == null || !server.isHealthy()) return;
        ObjectNode params = JSON.createObjectNode();
        ObjectNode td = JSON.createObjectNode();
        td.put("uri", toUri(filePath));
        params.set("textDocument", td);
        ObjectNode change = JSON.createObjectNode();
        change.put("text", newContent);
        params.set("contentChanges", JSON.createArrayNode().add(change));
        server.sendNotification("textDocument/didChange", params);
    }

    public void didSave(String filePath) {
        LspServer server = getServerForFile(filePath);
        if (server == null || !server.isHealthy()) return;
        ObjectNode params = JSON.createObjectNode();
        ObjectNode td = JSON.createObjectNode();
        td.put("uri", toUri(filePath));
        params.set("textDocument", td);
        server.sendNotification("textDocument/didSave", params);
    }

    public void didClose(String filePath) {
        LspServer server = getServerForFile(filePath);
        if (server == null) return;
        if (!server.isHealthy()) { openFiles.remove(filePath); return; }
        ObjectNode params = JSON.createObjectNode();
        ObjectNode td = JSON.createObjectNode();
        td.put("uri", toUri(filePath));
        params.set("textDocument", td);
        server.sendNotification("textDocument/didClose", params);
        openFiles.remove(filePath);
    }

    public void onDiagnostics(String filePath, java.util.function.Consumer<JsonNode> handler) {
        LspServer server = getServerForFile(filePath);
        if (server != null) server.onNotification("textDocument/publishDiagnostics", handler);
    }

    public Collection<LspServer> servers() { return Collections.unmodifiableCollection(servers.values()); }

    @Override
    public void close() {
        for (LspServer s : servers.values()) s.stop();
    }

    // ── Private ──

    private CompletableFuture<Void> ensureOpen(String filePath, LspServer server) {
        return ensureServerStarted(filePath).thenRun(() -> {
            if (!openFiles.containsKey(filePath)) {
                try {
                    String content = Files.readString(Path.of(filePath));
                    didOpen(filePath, content);
                } catch (Exception e) { /* best-effort */ }
            }
        });
    }

    private static String toUri(String path) {
        return Path.of(path).toUri().toString();
    }

    private ObjectNode textDocumentItem(String filePath, String content) {
        ObjectNode td = JSON.createObjectNode();
        td.put("uri", toUri(filePath));
        td.put("languageId", languageId(filePath));
        td.put("version", 1);
        td.put("text", content);
        return td;
    }

    private static String languageId(String path) {
        String ext = extension(path);
        return switch (ext != null ? ext : "") {
            case "ts", "tsx" -> "typescript";
            case "js", "jsx" -> "javascript";
            case "py" -> "python";
            case "java" -> "java";
            case "rs" -> "rust";
            case "go" -> "go";
            case "rb" -> "ruby";
            default -> ext != null ? ext : "plaintext";
        };
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : null;
    }
}
