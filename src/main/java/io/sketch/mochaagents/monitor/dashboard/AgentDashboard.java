package io.sketch.mochaagents.monitor.dashboard;

import io.sketch.mochaagents.monitor.MetricsCollector;
import java.util.Map;

/**
 * Agent 仪表板 — 可视化展示 Agent 运行状态与关键指标.
 * @author lanxia39@163.com
 */
public class AgentDashboard {

    private final MetricsCollector collector;

    public AgentDashboard(MetricsCollector collector) {
        this.collector = collector;
    }

    /** 获取运行摘要 */
    public DashboardSnapshot snapshot() {
        return new DashboardSnapshot(
                collector.getCounter("agent.calls"),
                collector.getCounter("agent.successes"),
                collector.getCounter("agent.errors"),
                collector.getSummary("agent.latency"),
                collector.getSummary("agent.tokens")
        );
    }

    public record DashboardSnapshot(
            long totalCalls,
            long successes,
            long errors,
            MetricsCollector.MetricSummary latency,
            MetricsCollector.MetricSummary tokens
    ) {
        public double successRate() {
            return totalCalls > 0 ? (double) successes / totalCalls : 1.0;
        }
    }
}
