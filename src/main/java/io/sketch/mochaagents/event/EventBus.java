// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central event bus — pub/sub for all agent lifecycle events.
 *
 * <pre>{@code
 * EventBus bus = new EventBus();
 * Runnable unsub = bus.subscribe(e -> log.info("{}: {}", e.type(), e.data()));
 * bus.fire(new AgentEvent(EventType.TOOL_CALL, "agent1", result, 42));
 * }</pre>
  * @author lanxia39@163.com
 */
public class EventBus {

    private final List<EventListener> listeners = new CopyOnWriteArrayList<>();

    public Runnable subscribe(EventListener l) { listeners.add(l); return () -> listeners.remove(l); }
    public void fire(AgentEvent e) { for (var l : listeners) l.onEvent(e); }

    @FunctionalInterface
    public interface EventListener {
        void onEvent(AgentEvent event);
    }
}
