// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent.event;

import io.sketch.mochaagents.event.AgentEvents;
import io.sketch.mochaagents.event.Subscribe;
import io.sketch.mochaagents.memory.MemoryManager;
import io.sketch.mochaagents.session.SessionManager;
import io.sketch.mochaagents.tool.FileHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Async persistence listener — handles all I/O on background threads.
 *
 * <p>Uses Guava-style &#64;Subscribe methods for type-safe event handling.
 * Registered on EventBus via {@code bus.register(listener)}.
 *
 * @author lanxia39@163.com
 */
public class AsyncPersistenceListener {

    private static final Logger log = LoggerFactory.getLogger(AsyncPersistenceListener.class);

    private final MemoryManager memory;
    private final SessionManager sessionStore;
    private final Supplier<SessionManager.Session> sessionSupplier;
    private final FileHistory fileHistory;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "async-persist");
        t.setDaemon(true);
        return t;
    });

    public AsyncPersistenceListener(MemoryManager memory, SessionManager sessionStore,
                                     Supplier<SessionManager.Session> sessionSupplier) {
        this.memory = memory;
        this.sessionStore = sessionStore;
        this.sessionSupplier = sessionSupplier;
        this.fileHistory = FileHistory.getInstance();
    }

    @Subscribe
    void onStepCompleted(AgentEvents.StepCompleted e) {
        String output = e.modelOutput();
        if (output != null && !output.isEmpty()) {
            async(() -> {
                var session = sessionSupplier.get();
                if (session != null) {
                    sessionStore.append(session, "assistant", output);
                    session.incrementMessageCount();
                }
            });
        }
        String obs = e.observation();
        if (obs != null && !obs.isEmpty() && !"[snipped]".equals(obs)) {
            async(() -> {
                var session = sessionSupplier.get();
                if (session != null) {
                    sessionStore.append(session, "system", "Observation: " + obs);
                    session.incrementMessageCount();
                }
            });
        }
    }

    @Subscribe
    void onToolCalled(AgentEvents.ToolCalled e) {
        String file = e.file();
        if (file != null && ("write_file".equals(e.toolName()) || "edit_file".equals(e.toolName()))) {
            async(() -> fileHistory.record(file, e.toolName()));
        }
    }

    @Subscribe
    void onCompleted(AgentEvents.Completed e) {
        async(() -> {
            var session = sessionSupplier.get();
            if (session != null) {
                try { sessionStore.saveMeta(session); }
                catch (java.io.IOException ignored) {}
            }
            for (var record : memory.snapshot()) {
                memory.save(record);
            }
        });
    }

    private void async(Runnable task) {
        CompletableFuture.runAsync(() -> {
            try { task.run(); }
            catch (Exception ex) { log.debug("Async persist failed: {}", ex.getMessage()); }
        }, executor);
    }

    public void shutdown() {
        executor.shutdown();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
