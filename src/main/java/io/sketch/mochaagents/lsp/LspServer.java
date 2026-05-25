// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LSP server instance — lifecycle, crash recovery, ContentModified retry.
 *
 * @author lanxia39@163.com
 */
public class LspServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LspServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int[] RETRY_BACKOFF_MS = {500, 1000, 2000};

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
        if (!state.compareAndSet(State.STOPPED, State.STARTING))
            return CompletableFuture.completedFuture(null);
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
                client.onNotification("exit", params -> handleCrash());
                state.set(State.RUNNING);
                log.info("LSP server {} started", name);
                f.complete(null);
            } catch (IOException e) {
                lastError = e.getMessage();
                state.set(State.ERROR);
                if (config.restartOnCrash() && restartCount < config.maxRestarts()) {
                    restartCount++;
                    log.warn("LSP server {} crashed, restart {}/{}: {}",
                            name, restartCount, config.maxRestarts(), e.getMessage());
                    state.set(State.STOPPED);
                    start().whenComplete((v, ex) -> { if (ex == null) f.complete(null); else f.completeExceptionally(ex); });
                } else {
                    f.completeExceptionally(e);
                    log.error("LSP server {} failed after {} restarts", name, restartCount);
                }
            }
        });
        CompletableFuture.runAsync(() -> {
            try { f.get(config.startupTimeout(), TimeUnit.SECONDS); }
            catch (TimeoutException e) { f.completeExceptionally(new TimeoutException("Startup timeout: " + name)); }
            catch (Exception ignored) {}
        });
        return f;
    }

    /** Send request with ContentModified retry (-32801). */
    public CompletableFuture<JsonNode> sendRequest(String method, JsonNode params) {
        if (state.get() != State.RUNNING || client == null)
            return CompletableFuture.failedFuture(new IllegalStateException("Server not running: " + name));
        return sendWithRetry(method, params, 0);
    }

    private CompletableFuture<JsonNode> sendWithRetry(String method, JsonNode params, int attempt) {
        return client.sendRequest(method, params).exceptionallyCompose(ex -> {
            if (isContentModified(ex) && attempt < RETRY_BACKOFF_MS.length) {
                int delay = RETRY_BACKOFF_MS[attempt];
                log.debug("LSP {} ContentModified, retry in {}ms", name, delay);
                CompletableFuture<JsonNode> retry = new CompletableFuture<>();
                CompletableFuture.runAsync(() -> {
                    try { Thread.sleep(delay); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    sendWithRetry(method, params, attempt + 1)
                            .whenComplete((r, e) -> { if (e != null) retry.completeExceptionally(e); else retry.complete(r); });
                });
                return retry;
            }
            return CompletableFuture.failedFuture(ex);
        });
    }

    private static boolean isContentModified(Throwable ex) {
        return ex.getCause() instanceof LspClient.LspException le
                && le.getMessage() != null && le.getMessage().contains("-32801");
    }

    public void sendNotification(String method, JsonNode params) {
        if (state.get() == State.RUNNING && client != null)
            client.sendNotification(method, params);
    }

    public void onNotification(String method, java.util.function.Consumer<JsonNode> handler) {
        if (client != null) client.onNotification(method, handler);
    }

    private void handleCrash() {
        if (state.get() == State.RUNNING && config.restartOnCrash() && restartCount < config.maxRestarts()) {
            restartCount++;
            state.set(State.STOPPED);
            log.warn("LSP server {} crashed, restarting {}/{}", name, restartCount, config.maxRestarts());
            start();
        }
    }

    public boolean isHealthy() { return state.get() == State.RUNNING && client != null && client.isRunning(); }
    public String name() { return name; }
    public State state() { return state.get(); }
    public LspServerConfig config() { return config; }

    @Override
    public void close() {
        if (state.compareAndSet(State.RUNNING, State.STOPPING)) {
            try { client.close(); } catch (Exception e) { log.warn("LSP server {} close: {}", name, e.getMessage()); }
            state.set(State.STOPPED);
        }
    }

    public void stop() { close(); }

    public record LspServerConfig(
            String command, String[] args, int startupTimeout, int shutdownTimeout,
            boolean restartOnCrash, int maxRestarts) {

        public LspServerConfig(String command, String[] args) {
            this(command, args, 30, 5, true, 3);
        }
        public LspServerConfig(String command) {
            this(command, new String[0]);
        }
    }
}
