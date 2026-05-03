package io.sketch.mochaagents.monitor.dashboard;

import io.sketch.mochaagents.monitor.TraceSpan;
import java.util.List;

/**
 * 执行查看器 — 展示 Agent 执行步骤的详细追踪信息.
 * @author lanxia39@163.com
 */
public class ExecutionViewer {

    private final List<TraceSpan> currentTrace;

    public ExecutionViewer(List<TraceSpan> trace) {
        this.currentTrace = List.copyOf(trace);
    }

    /** 获取执行时间线 */
    public List<StepView> timeline() {
        return currentTrace.stream()
                .map(s -> new StepView(s.name(), s.durationMs(), s.status()))
                .toList();
    }

    /** 获取总耗时 */
    public long totalDurationMs() {
        return currentTrace.stream()
                .mapToLong(TraceSpan::durationMs)
                .sum();
    }

    /** 获取步骤数 */
    public int stepCount() { return currentTrace.size(); }

    public record StepView(String name, long durationMs, String status) {}
}
