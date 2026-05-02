package io.sketch.mochaagents.safety.audit;

import java.util.*;

/**
 * 执行追踪器 — 追踪每一步操作的输入、输出、耗时与状态.
 */
public class ExecutionTracer {

    private final List<TraceRecord> traces = new ArrayList<>();

    /** 开始追踪 */
    public TraceSpan startSpan(String operation, String agentId) {
        TraceSpan span = new TraceSpan(UUID.randomUUID().toString(), operation, agentId, System.currentTimeMillis());
        return span;
    }

    /** 结束追踪 */
    public void endSpan(TraceSpan span, Object output, String status) {
        span.end(output, status);
        traces.add(span.toRecord());
    }

    /** 获取所有追踪记录 */
    public List<TraceRecord> getTraces() { return List.copyOf(traces); }

    public static class TraceSpan {
        private final String spanId;
        private final String operation;
        private final String agentId;
        private final long startTime;
        private long endTime;
        private Object output;
        private String status;

        TraceSpan(String spanId, String operation, String agentId, long startTime) {
            this.spanId = spanId; this.operation = operation; this.agentId = agentId; this.startTime = startTime;
        }

        void end(Object output, String status) {
            this.endTime = System.currentTimeMillis();
            this.output = output;
            this.status = status;
        }

        TraceRecord toRecord() {
            return new TraceRecord(spanId, operation, agentId, startTime, endTime,
                    output != null ? output.toString() : null, status, endTime - startTime);
        }
    }

    public record TraceRecord(String spanId, String operation, String agentId,
                               long startTime, long endTime, String output,
                               String status, long durationMs) {}
}
