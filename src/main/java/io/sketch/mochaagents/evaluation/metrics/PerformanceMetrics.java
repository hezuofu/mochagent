package io.sketch.mochaagents.evaluation.metrics;

/**
 * 性能指标 — 评估响应的延迟、token 消耗、吞吐量.
 */
public class PerformanceMetrics {

    /** 评估延迟 (ms) */
    public double latencyScore(long latencyMs, long thresholdMs) {
        return Math.max(0.0, 1.0 - (double) latencyMs / thresholdMs);
    }

    /** Token 效率 */
    public double tokenEfficiency(int outputTokens, int expectedMaxTokens) {
        if (expectedMaxTokens <= 0) return 1.0;
        return Math.max(0.0, 1.0 - (double) outputTokens / expectedMaxTokens);
    }

    /** 吞吐量 (tokens/s) */
    public double throughput(int totalTokens, long durationMs) {
        if (durationMs <= 0) return 0;
        return (double) totalTokens / (durationMs / 1000.0);
    }
}
