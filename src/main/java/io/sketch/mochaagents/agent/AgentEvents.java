package io.sketch.mochaagents.agent;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent event bus — pub/sub for real-time execution notifications.
 * <p>Subscribe to get notified of every step, tool call, LLM interaction, and error.
 * @author lanxia39@163.com
 */
public final class AgentEvents {

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Subscribe to all execution events. Returns unsubscribe handle. */
    public Runnable subscribe(Listener l) { listeners.add(l); return () -> listeners.remove(l); }

    // Fire events (public for agent impl access)
    public void fire(Event e) { for (var l : listeners) l.onEvent(e); }

    // -- Event types --------------------------------------------------

    /** Base event with timestamp and agent name. */
    public record Event(String type, String agentName, Object data, long elapsedMs, Instant timestamp) {
        public Event(String type, String agentName, Object data, long elapsedMs) {
            this(type, agentName, data, elapsedMs, Instant.now());
        }
        public boolean is(String t) { return type.equals(t); }
    }

    // Predefined event types
    public static final String STARTED = "agent.started";
    public static final String STEP_START = "step.start";
    public static final String STEP_END = "step.end";
    public static final String LLM_CALL = "llm.call";
    public static final String TOOL_CALL = "tool.call";
    public static final String FINAL_ANSWER = "agent.final_answer";
    public static final String COMPLETED = "agent.completed";
    public static final String ERROR = "agent.error";
    public static final String COST = "agent.cost";

    /** Event listener — single callback for all events. Filter by type if needed. */
    @FunctionalInterface
    public interface Listener {
        void onEvent(Event event);
    }
}
