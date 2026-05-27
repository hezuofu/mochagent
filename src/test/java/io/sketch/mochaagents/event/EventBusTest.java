// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.event;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventBusTest {

    @Test void typedEventsDispatchToSubscribers() {
        var bus = new EventBus();
        var count = new AtomicInteger();
        bus.on(TestEvent.class, e -> count.incrementAndGet());
        bus.post(new TestEvent("data"));
        assertEquals(1, count.get());
    }

    @Test void unsubscribeStopsReceiving() {
        var bus = new EventBus();
        var count = new AtomicInteger();
        var unsub = bus.on(TestEvent.class, e -> count.incrementAndGet());
        bus.post(new TestEvent("first"));
        unsub.run();
        bus.post(new TestEvent("second"));
        assertEquals(1, count.get());
    }

    @Test void multipleSubscribersAllFire() {
        var bus = new EventBus();
        var c1 = new AtomicInteger(); var c2 = new AtomicInteger();
        bus.on(TestEvent.class, e -> c1.incrementAndGet());
        bus.on(TestEvent.class, e -> c2.incrementAndGet());
        bus.post(new TestEvent("x"));
        assertEquals(1, c1.get());
        assertEquals(1, c2.get());
    }

    @Test void registerSubscriberWithAnnotation() {
        var bus = new EventBus();
        var count = new AtomicInteger();
        bus.register(new Object() {
            @Subscribe
            void handle(TestEvent e) { count.incrementAndGet(); }
        });
        bus.post(new TestEvent("hello"));
        assertEquals(1, count.get());
    }

    @Test void differentEventTypesAreNotCrossDispatched() {
        var bus = new EventBus();
        var count = new AtomicInteger();
        bus.on(TestEvent.class, e -> count.incrementAndGet());
        bus.post(new OtherEvent("other"));
        assertEquals(0, count.get(), "OtherEvent should not trigger TestEvent handler");
    }

    @Test void supertypeDispatchViaAssignable() {
        var bus = new EventBus();
        var count = new AtomicInteger();
        // Register for parent type
        bus.register(new Object() {
            @Subscribe
            void handle(TestEvent e) { count.incrementAndGet(); }
        });
        // Post subtype
        bus.post(new TestEventSub("child"));
        assertEquals(1, count.get());
    }

    @Test void onDeadEventFiresWhenNoHandler() {
        var bus = new EventBus();
        var dead = new AtomicInteger();
        bus.onDeadEvent(e -> dead.incrementAndGet());
        bus.post(new OtherEvent("no-handler"));
        assertEquals(1, dead.get());
    }

    // ── Test event types ──

    static class TestEvent {
        final String payload;
        TestEvent(String p) { this.payload = p; }
    }
    static class TestEventSub extends TestEvent {
        TestEventSub(String p) { super(p); }
    }
    record OtherEvent(String payload) {}
}
