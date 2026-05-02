package io.sketch.mochaagents.evaluation;

import java.util.*;

/**
 * 评估标准 — 定义评估的维度、权重与阈值.
 */
public class EvaluationCriteria {

    private final Map<String, Double> weights;
    private final Map<String, Double> thresholds;

    public EvaluationCriteria(Map<String, Double> weights, Map<String, Double> thresholds) {
        this.weights = Map.copyOf(weights);
        this.thresholds = Map.copyOf(thresholds);
    }

    public static EvaluationCriteria defaultCriteria() {
        return new EvaluationCriteria(
                Map.of("accuracy", 0.4, "relevance", 0.2, "safety", 0.2, "efficiency", 0.1, "completeness", 0.1),
                Map.of("accuracy", 0.7, "safety", 0.9)
        );
    }

    public double weight(String dimension) { return weights.getOrDefault(dimension, 0.0); }
    public double threshold(String dimension) { return thresholds.getOrDefault(dimension, 0.5); }
    public Set<String> dimensions() { return weights.keySet(); }
}
