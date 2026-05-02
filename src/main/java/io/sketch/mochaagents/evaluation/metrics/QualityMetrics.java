package io.sketch.mochaagents.evaluation.metrics;

import java.util.Map;

/**
 * 质量指标 — 评估输出的准确性、相关性、完整性.
 */
public class QualityMetrics {

    /** 计算准确度 (简单的关键信息覆盖) */
    public double accuracy(String output, String expected) {
        if (expected == null || expected.isEmpty()) return 0.5;
        String[] expectedWords = expected.toLowerCase().split("\\s+");
        String lowerOutput = output.toLowerCase();
        long matches = java.util.stream.Stream.of(expectedWords).filter(lowerOutput::contains).count();
        return (double) matches / expectedWords.length;
    }

    /** 计算相关性 */
    public double relevance(String output, String query) {
        if (query == null || query.isEmpty()) return 0.5;
        String[] queryWords = query.toLowerCase().split("\\s+");
        String lowerOutput = output.toLowerCase();
        long matches = java.util.stream.Stream.of(queryWords).filter(lowerOutput::contains).count();
        return (double) matches / queryWords.length;
    }

    /** 完整性评分 */
    public double completeness(String output, int expectedLength) {
        if (expectedLength <= 0) return 1.0;
        return Math.min(1.0, (double) output.length() / expectedLength);
    }
}
