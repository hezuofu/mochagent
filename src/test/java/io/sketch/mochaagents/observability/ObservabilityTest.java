// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.observability;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ObservabilityTest {

    @Test void noopDoesNothing() {
        var obs = Observability.noop();
        obs.counter("test", 1);
        obs.gauge("test", 1.0);
        try (var span = obs.startSpan("test")) { span.tag("k", "v"); }
        // No exception = pass
    }

    @Test void customCounterIncrements() {
        var count = new AtomicInteger();
        var obs = new Observability() {
            public void counter(String name, long delta) { count.addAndGet((int) delta); }
            public Span startSpan(String name) { return new Noop().startSpan(name); }
        };
        obs.increment("test");
        obs.counter("test", 3);
        assertEquals(4, count.get());
    }

    @Test void spanAutoCloses() {
        var closed = new AtomicInteger();
        var obs = new Observability() {
            public void counter(String n, long d) {}
            public Span startSpan(String n) {
                return new Span() {
                    public void tag(String k, String v) {}
                    public void close() { closed.incrementAndGet(); }
                };
            }
        };
        try (var s = obs.startSpan("test")) { s.tag("k", "v"); }
        assertEquals(1, closed.get());
    }
}
