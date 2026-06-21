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
 * <p>Dispatches by event class — each handler receives only its declared type:
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

    /** Synchronous EventBus — handlers run on posting thread (Guava default). */
    public EventBus() { this(null, false); }

    /** Async EventBus with daemon thread pool. */
    public static EventBus async() {
        return new EventBus(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "event-bus");
            t.setDaemon(true);
            return t;
        }), true);
    }

    /** Sync EventBus — explicit factory. */
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
            boolean sync = ann.sync() || !async;
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
    @SuppressWarnings("unchecked")
    public <T> Runnable on(Class<T> eventType, Consumer<T> handler) {
        Handler h = new Handler((Consumer<Object>) handler, !async);
        List<Handler> list = handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>());
        list.add(h);
        return () -> list.remove(h);
    }

    // ── Post ──

    /** Post an event — dispatched to handlers registered for the event's type or supertypes. */
    public void post(Object event) {
        boolean handled = false;
        Class<?> type = event.getClass();

        // Walk class hierarchy: exact class → superclass → interfaces
        while (type != null && type != Object.class) {
            if (dispatchTo(type, event)) handled = true;
            for (Class<?> iface : type.getInterfaces())
                if (dispatchTo(iface, event)) handled = true;
            type = type.getSuperclass();
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

    @SuppressWarnings("unchecked")
    private void invoke(Handler h, Object event) {
        try {
            if (h.method != null) {
                h.method.invoke(h.subscriber, event);
            } else if (h.consumer != null) {
                ((Consumer<Object>) h.consumer).accept(event);
            }
        } catch (InvocationTargetException e) {
            log.error("Event handler {} failed: {}", h.method.getName(), e.getCause().getMessage());
        } catch (Exception e) {
            log.error("Event handler error: {}", e.getMessage());
        }
    }

    // ── Dead events ──

    /** Handle events that have no subscribers. */
    public void onDeadEvent(Consumer<Object> handler) { this.deadEventHandler = handler; }

    // ── Shutdown ──

    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try { executor.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // ── Internal ──

    private static class Handler {
        final Object subscriber;
        final Method method;
        final boolean sync;
        final Consumer<Object> consumer;

        Handler(Object subscriber, Method method, boolean sync) {
            this.subscriber = subscriber;
            this.method = method;
            this.sync = sync;
            this.consumer = null;
        }

        Handler(Consumer<Object> consumer, boolean sync) {
            this.subscriber = null;
            this.method = null;
            this.sync = sync;
            this.consumer = consumer;
        }
    }
}
