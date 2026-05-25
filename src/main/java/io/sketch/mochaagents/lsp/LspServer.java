// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LSP server instance — lifecycle, health, retry.
 *
 * @author lanxia39@163.com
 */
public class LspServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LspServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String name;
    private final LspServerConfig config;
    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private LspClient client;
    private int restartCount;
    private long startTime;
    private String lastError;

    public enum State { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

    public LspServer(String name, LspServerConfig config) { this.name = name; this.config = config; }

    public CompletableFuture<Void> start() {
        if (!state.compareAndSet(State.STOPPED, State.STARTING)) {
            return CompletableFuture.completedFuture(null);
        }
        restartCount = 0;
        startTime = System.currentTimeMillis();
        return doStart();
    }

    private CompletableFuture<Void> doStart() {
        CompletableFuture<Void> f = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                client = new LspClient();
                client.start(config.command(), config.args());
                state.set(State.RUNNING);
                log.info("LSP server {} started", name);
                f.complete(null);
            } catch (IOException e) {
                lastError = e.getMessage();
                state.set(State.ERROR);
                f.completeExceptionally(e);
                log.warn("LSP server {} failed to start: {}", name, e.getMessage());
            }
        });
        CompletableFuture.runAsync(() -> {
            try { f.get(config.startupTimeout(), TimeUnit.SECONDS); }
            catch (TimeoutException e) { f.completeExceptionally(new TimeoutException("Startup timeout for " + name)); }
            catch (Exception ignored) {}
        });
        return f;
    }

    public <T> CompletableFuture<T> sendRequest(String method, JsonNode params) {
        if (state.get() != State.RUNNING || client == null)
            return CompletableFuture.failedFuture(new IllegalStateException("Server not running: " + name));
        return client.sendRequest(method, params).thenApply(r -> (T) r);
    }

    public void sendNotification(String method, JsonNode params) {
        if (state.get() == State.RUNNING && client != null)
            client.sendNotification(method, params);
    }

    public void onNotification(String method, java.util.function.Consumer<JsonNode> handler) {
        if (client != null) client.onNotification(method, handler);
    }

    public boolean isHealthy() { return state.get() == State.RUNNING && client != null && client.isRunning(); }

    public String name() { return name; }
    public State state() { return state.get(); }

    @Override
    public void close() {
        if (state.compareAndSet(State.RUNNING, State.STOPPING)) {
            try { client.close(); }
            catch (Exception e) { log.warn("Error closing LSP server {}: {}", name, e.getMessage()); }
            state.set(State.STOPPED);
        }
    }

    public void stop() { close(); }

    public record LspServerConfig(String command, String[] args, int startupTimeout) {
        public LspServerConfig(String command) { this(command, new String[0], 30); }
        public LspServerConfig(String command, String[] args) { this(command, args, 30); }
    }
}
