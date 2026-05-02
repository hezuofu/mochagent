package io.sketch.mochaagents.plan;

import java.util.Map;

/**
 * 执行反馈.
 */
public final class ExecutionFeedback {

    private final String stepId;
    private final ExecutionStatus status;
    private final Object result;
    private final String error;
    private final Map<String, Object> metrics;
    private final long executionTimeMs;

    public ExecutionFeedback(String stepId, ExecutionStatus status, Object result, String error,
                             Map<String, Object> metrics, long executionTimeMs) {
        this.stepId = stepId;
        this.status = status;
        this.result = result;
        this.error = error;
        this.metrics = Map.copyOf(metrics);
        this.executionTimeMs = executionTimeMs;
    }

    public String stepId() { return stepId; }
    public ExecutionStatus status() { return status; }
    public Object result() { return result; }
    public String error() { return error; }
    public Map<String, Object> metrics() { return metrics; }
    public long executionTimeMs() { return executionTimeMs; }
    public boolean isSuccessful() { return status == ExecutionStatus.SUCCESS; }
    public boolean shouldReplan() { return status == ExecutionStatus.FAILED; }

    public enum ExecutionStatus { SUCCESS, FAILED, PARTIAL }
}
