// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.event;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventBusTest {

    @Test void firesEventsToSubscribers() {
        var bus = new EventBus();
        var count = new AtomicInteger();
        bus.subscribe(e -> count.incrementAndGet());
        bus.fire(new AgentEvent(EventType.TOOL_CALL, "agent1", "data", 42));
        assertEquals(1, count.get());
    }

    @Test void unsubscribeStopsReceiving() {
        var bus = new EventBus();
        var count = new AtomicInteger();
        var unsub = bus.subscribe(e -> count.incrementAndGet());
        bus.fire(new AgentEvent(EventType.STARTED, "a", null, 0));
        unsub.run();
        bus.fire(new AgentEvent(EventType.STARTED, "a", null, 0));
        assertEquals(1, count.get());
    }

    @Test void multipleSubscribersAllFire() {
        var bus = new EventBus();
        var c1 = new AtomicInteger(); var c2 = new AtomicInteger();
        bus.subscribe(e -> c1.incrementAndGet());
        bus.subscribe(e -> c2.incrementAndGet());
        bus.fire(new AgentEvent(EventType.ERROR, "a", "err", 0));
        assertEquals(1, c1.get());
        assertEquals(1, c2.get());
    }

    @Test void eventTypeFiltering() {
        var event = new AgentEvent(EventType.MODEL_CALL, "a", null, 0);
        assertTrue(event.is(EventType.MODEL_CALL));
        assertFalse(event.is(EventType.TOOL_CALL));
    }
}
