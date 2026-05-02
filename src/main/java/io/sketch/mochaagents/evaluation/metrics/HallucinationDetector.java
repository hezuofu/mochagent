package io.sketch.mochaagents.evaluation.metrics;

import java.util.List;

/**
 * 幻觉检测器 — 检测 AI 输出中的虚构内容和事实性错误.
 */
public class HallucinationDetector {

    private final List<String> hallucinationMarkers;

    public HallucinationDetector(List<String> markers) {
        this.hallucinationMarkers = List.copyOf(markers);
    }

    public HallucinationDetector() {
        this(List.of("I'm not sure", "I think", "probably", "might be",
                "could be", "as an AI", "I don't know", "not certain"));
    }

    /** 幻觉风险评分 (0.0 = 低风险, 1.0 = 高风险) */
    public double hallucinationRisk(String output) {
        if (output == null || output.isEmpty()) return 0.0;
        String lower = output.toLowerCase();
        long markers = hallucinationMarkers.stream()
                .filter(m -> lower.contains(m.toLowerCase()))
                .count();
        return Math.min(1.0, markers * 0.15);
    }

    /** 检测到的幻觉标记 */
    public List<String> detectMarkers(String output) {
        String lower = output.toLowerCase();
        return hallucinationMarkers.stream()
                .filter(m -> lower.contains(m.toLowerCase()))
                .map(m -> "Hallucination marker: " + m)
                .toList();
    }
}
