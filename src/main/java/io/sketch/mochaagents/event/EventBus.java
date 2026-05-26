// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Guava-style EventBus — typed events, &#64;Subscribe dispatch, async support.
 *
 * <p>Unlike the simple {@link io.sketch.mochaagents.agent.event.AgentEvents}
 * (single callback, manual type filtering), this bus dispatches by event class:
 *
 * <pre>{@code
 * var bus = new EventBus();
 *
 * // Register typed handlers
 * bus.register(new Object() {
 *     &#64;Subscribe void onStep(StepCompleted e) { save(e); }
 *     &#64;Subscribe void onFile(FileModified e) { snapshot(e); }
 *     &#64;Subscribe(sync = true) void onError(AgentError e) { alert(e); }
 * });
 *
 * // Post events — automatic dispatch by type
 * bus.post(new StepCompleted("agent", 3, "output", "obs", 42));
 * bus.post(new FileModified("/path/Foo.java", "edit_file", null, "new content"));
 * }</pre>
 *
 * @author lanxia39@163.com
 */
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final Map<Class<?>, List<Handler>> handlers = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final boolean async;
    private Consumer<Object> deadEventHandler;

    public EventBus() {
        this(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "event-bus");
            t.setDaemon(true);
            return t;
        }), true);
    }

    /** Synchronous EventBus — handlers run on posting thread. */
    public static EventBus sync() { return new EventBus(null, false); }

    /** Async EventBus with custom executor. */
    public static EventBus async(ExecutorService executor) { return new EventBus(executor, true); }

    private EventBus(ExecutorService executor, boolean async) {
        this.executor = executor;
        this.async = async;
    }

    // ── Register / Unregister ──

    /** Scan subscriber for &#64;Subscribe methods and register them. */
    public void register(Object subscriber) {
        for (Method m : subscriber.getClass().getDeclaredMethods()) {
            Subscribe ann = m.getAnnotation(Subscribe.class);
            if (ann == null) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 1) {
                log.warn("EventListener method {} must have exactly 1 parameter, got {}", m.getName(), params.length);
                continue;
            }
            Class<?> eventType = params[0];
            m.setAccessible(true);
            boolean sync = ann.sync() || !async; // explicit sync or sync bus
            handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                    .add(new Handler(subscriber, m, sync));
        }
    }

    /** Unregister all handlers for a subscriber. */
    public void unregister(Object subscriber) {
        for (var entry : handlers.entrySet()) {
            entry.getValue().removeIf(h -> h.subscriber == subscriber);
        }
    }

    /** Convenience: register a lambda for a specific event type. */
    public <T> Runnable on(Class<T> eventType, Consumer<T> handler) {
        Object sub = new Object() {
            @Subscribe void handle(T event) { handler.accept(event); }
        };
        register(sub);
        return () -> unregister(sub);
    }

    // ── Post ──

    /** Post an event — dispatched to all matching handlers by type. */
    public void post(Object event) {
        boolean handled = false;
        Class<?> eventClass = event.getClass();

        // Walk class hierarchy (exact match)
        Class<?> type = eventClass;
        while (type != null && type != Object.class) {
            if (dispatchTo(type, event)) handled = true;
            for (Class<?> iface : type.getInterfaces())
                if (dispatchTo(iface, event)) handled = true;
            type = type.getSuperclass();
        }

        // Also check registered types for assignable match (backward compat:
        // a Started event should trigger listeners registered for AgentEvent supertype)
        for (var entry : handlers.entrySet()) {
            if (entry.getKey().isAssignableFrom(eventClass) && entry.getKey() != eventClass) {
                // Only if not already dispatched above (exact class chain)
                List<Handler> list = entry.getValue();
                if (list != null) {
                    for (Handler h : list) dispatch(h, event);
                    handled = true;
                }
            }
        }

        if (!handled && deadEventHandler != null) {
            deadEventHandler.accept(event);
        }
    }

    private boolean dispatchTo(Class<?> type, Object event) {
        List<Handler> list = handlers.get(type);
        if (list == null) return false;
        for (Handler h : list) dispatch(h, event);
        return true;
    }

    private void dispatch(Handler h, Object event) {
        if (h.sync || !async) {
            invoke(h, event);
        } else {
            executor.execute(() -> invoke(h, event));
        }
    }

    private void invoke(Handler h, Object event) {
        try {
            h.method.invoke(h.subscriber, event);
        } catch (InvocationTargetException e) {
            log.error("Event handler {} failed: {}", h.method.getName(), e.getCause().getMessage());
        } catch (Exception e) {
            log.error("Event handler {} error: {}", h.method.getName(), e.getMessage());
        }
    }

    // ── Dead events ──

    /** Handle events that have no subscribers. */
    public void onDeadEvent(Consumer<Object> handler) { this.deadEventHandler = handler; }

    // ── Backward-compat API (old AgentEvent/EventListener pattern) ──

    /** @deprecated Use post(Object) with typed event records instead. */
    @Deprecated
    public void fire(AgentEvent e) { post(e); }

    /** @deprecated Use register(Object) with &#64;Subscribe methods instead. */
    @Deprecated
    public Runnable subscribe(EventListener l) {
        Object sub = new Object() {
            @Subscribe(sync = true)
            void handle(AgentEvent e) { l.onEvent(e); }
        };
        register(sub);
        return () -> unregister(sub);
    }

    // ── Shutdown ──

    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try { executor.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // ── Legacy types ──

    @FunctionalInterface
    public interface EventListener {
        void onEvent(AgentEvent event);
    }

    // ── Internal ──

    private record Handler(Object subscriber, Method method, boolean sync) {}
}
