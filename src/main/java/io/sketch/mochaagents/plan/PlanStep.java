package io.sketch.mochaagents.plan;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划步骤 — 单个执行单元.
 */
public class PlanStep {

    private final String stepId;
    private final String description;
    private final StepType type;
    private final List<String> dependencies;
    private final String agentId;
    private final Map<String, Object> parameters;
    private final int priority;
    private StepStatus status;
    private ExecutionResult result;
    private long startTime;
    private long endTime;

    private PlanStep(Builder builder) {
        this.stepId = builder.stepId;
        this.description = builder.description;
        this.type = builder.type;
        this.dependencies = List.copyOf(builder.dependencies);
        this.agentId = builder.agentId;
        this.parameters = Map.copyOf(builder.parameters);
        this.priority = builder.priority;
        this.status = StepStatus.PENDING;
    }

    public String stepId() { return stepId; }
    public String description() { return description; }
    public StepType type() { return type; }
    public List<String> dependencies() { return dependencies; }
    public String agentId() { return agentId; }
    public Map<String, Object> parameters() { return parameters; }
    public int priority() { return priority; }
    public StepStatus status() { return status; }
    public void markRunning() { this.status = StepStatus.RUNNING; this.startTime = System.currentTimeMillis(); }
    public void markSuccess(ExecutionResult result) { this.status = StepStatus.SUCCESS; this.result = result; this.endTime = System.currentTimeMillis(); }
    public void markFailed(ExecutionResult result) { this.status = StepStatus.FAILED; this.result = result; this.endTime = System.currentTimeMillis(); }
    public ExecutionResult result() { return result; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String stepId;
        private String description = "";
        private StepType type = StepType.ACTION;
        private List<String> dependencies = List.of();
        private String agentId = "";
        private final Map<String, Object> parameters = new HashMap<>();
        private int priority = 5;

        public Builder stepId(String stepId) { this.stepId = stepId; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder type(StepType type) { this.type = type; return this; }
        public Builder dependencies(List<String> deps) { this.dependencies = deps; return this; }
        public Builder agentId(String agentId) { this.agentId = agentId; return this; }
        public Builder parameter(String key, Object value) { this.parameters.put(key, value); return this; }
        public Builder priority(int priority) { this.priority = priority; return this; }

        public PlanStep build() { return new PlanStep(this); }
    }

    // Enums
    public enum StepType { THINKING, ACTION, OBSERVATION, DECISION, VALIDATION, COORDINATION }
    public enum StepStatus { PENDING, READY, RUNNING, SUCCESS, FAILED, SKIPPED, RETRYING }
    public enum PlanStatus { DRAFT, VALIDATED, IN_PROGRESS, PAUSED, COMPLETED, FAILED, CANCELLED }
}
