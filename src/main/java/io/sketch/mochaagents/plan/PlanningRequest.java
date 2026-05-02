package io.sketch.mochaagents.plan;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划请求.
 */
public final class PlanningRequest<T> {

    private final String requestId;
    private final T goal;
    private final String context;
    private final List<String> availableResources;
    private final Map<String, Object> constraints;
    private final PlanningMode mode;
    private final int maxSteps;
    private final long timeoutMs;
    private final Map<String, Object> metadata;

    private PlanningRequest(Builder<T> builder) {
        this.requestId = builder.requestId;
        this.goal = builder.goal;
        this.context = builder.context;
        this.availableResources = List.copyOf(builder.availableResources);
        this.constraints = Map.copyOf(builder.constraints);
        this.mode = builder.mode;
        this.maxSteps = builder.maxSteps;
        this.timeoutMs = builder.timeoutMs;
        this.metadata = Map.copyOf(builder.metadata);
    }

    public String requestId() { return requestId; }
    public T goal() { return goal; }
    public String context() { return context; }
    public List<String> availableResources() { return availableResources; }
    public Map<String, Object> constraints() { return constraints; }
    public PlanningMode mode() { return mode; }
    public int maxSteps() { return maxSteps; }
    public long timeoutMs() { return timeoutMs; }
    public Map<String, Object> metadata() { return metadata; }

    public enum PlanningMode { AUTO, MANUAL, HYBRID }

    public static <T> Builder<T> builder() { return new Builder<>(); }

    public static final class Builder<T> {
        private String requestId = java.util.UUID.randomUUID().toString();
        private T goal;
        private String context = "";
        private List<String> availableResources = List.of();
        private final Map<String, Object> constraints = new HashMap<>();
        private PlanningMode mode = PlanningMode.AUTO;
        private int maxSteps = 20;
        private long timeoutMs = 300_000;
        private final Map<String, Object> metadata = new HashMap<>();

        public Builder<T> requestId(String id) { this.requestId = id; return this; }
        public Builder<T> goal(T goal) { this.goal = goal; return this; }
        public Builder<T> context(String context) { this.context = context; return this; }
        public Builder<T> availableResources(List<String> res) { this.availableResources = res; return this; }
        public Builder<T> constraint(String key, Object value) { this.constraints.put(key, value); return this; }
        public Builder<T> mode(PlanningMode mode) { this.mode = mode; return this; }
        public Builder<T> maxSteps(int maxSteps) { this.maxSteps = maxSteps; return this; }
        public Builder<T> timeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; return this; }
        public Builder<T> metadata(String key, Object value) { this.metadata.put(key, value); return this; }

        public PlanningRequest<T> build() { return new PlanningRequest<>(this); }
    }
}
