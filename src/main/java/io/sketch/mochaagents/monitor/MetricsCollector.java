package io.sketch.mochaagents.monitor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 指标收集器 — 收集 Agent 运行时的各项指标 (调用次数、成功率、延迟等).
 * @author lanxia39@163.com
 */
public class MetricsCollector {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, List<Double>> histograms = new ConcurrentHashMap<>();

    /** 计数器加一 */
    public void incrementCounter(String name) {
        counters.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
    }

    /** 记录值 */
    public void recordValue(String name, double value) {
        histograms.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>())).add(value);
    }

    /** 获取计数器值 */
    public long getCounter(String name) {
        AtomicLong counter = counters.get(name);
        return counter != null ? counter.get() : 0;
    }

    /** 获取统计摘要 */
    public MetricSummary getSummary(String name) {
        List<Double> values = histograms.getOrDefault(name, List.of());
        if (values.isEmpty()) return new MetricSummary(name, 0, 0, 0, 0, 0);

        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        double avg = sum / values.size();
        double min = values.stream().min(Double::compare).orElse(0.0);
        double max = values.stream().max(Double::compare).orElse(0.0);

        return new MetricSummary(name, values.size(), sum, avg, min, max);
    }

    /** 重置所有指标 */
    public void reset() {
        counters.clear();
        histograms.clear();
    }

    public record MetricSummary(String name, int count, double sum, double avg, double min, double max) {}
}
