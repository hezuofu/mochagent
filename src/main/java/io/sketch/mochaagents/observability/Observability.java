// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.observability;

/**
 * Unified observability — metrics, tracing, and event hooks.
 *
 * <p>Implementations: noop (default), Micrometer, OpenTelemetry, Log4j.
 * Wire via {@code MochaAgent.builder().observability(impl)}.
 *
 * @author lanxia39@163.com
 */
public interface Observability {

    // ── Metrics ──

    void counter(String name, long delta);
    default void increment(String name) { counter(name, 1); }
    default void gauge(String name, double value) {}
    default void histogram(String name, double value) {}

    // ── Tracing ──

    Span startSpan(String name);

    // ── No-op ──

    static Observability noop() { return Noop.INSTANCE; }

    // ── Span ──

    interface Span extends AutoCloseable {
        void tag(String key, String value);
        default void event(String name) {}
        default void error(Throwable e) {}
        void close(); // auto-records duration on try-with-resources
    }

    final class Noop implements Observability {
        static final Observability INSTANCE = new Noop();
        private static final Span NOOP_SPAN = new Span() {
            public void tag(String k, String v) {}
            public void close() {}
        };
        public void counter(String n, long d) {}
        public Span startSpan(String n) { return NOOP_SPAN; }
    }
}
