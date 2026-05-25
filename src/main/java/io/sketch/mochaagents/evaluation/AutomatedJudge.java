// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.evaluation;


import java.util.*;

/**
 * 自动评判 — 综合多维指标自动评估 Agent 输出.
 * @author lanxia39@163.com
 */
public class AutomatedJudge implements Evaluator {

    private final QualityMetrics qualityMetrics;
    private final SafetyMetrics safetyMetrics;
    private final PerformanceMetrics performanceMetrics;
    private final HallucinationDetector hallucinationDetector;

    public AutomatedJudge() {
        this.qualityMetrics = new QualityMetrics();
        this.safetyMetrics = new SafetyMetrics();
        this.performanceMetrics = new PerformanceMetrics();
        this.hallucinationDetector = new HallucinationDetector();
    }

    @Override
    public EvaluationResult evaluate(String input, String output, String expected) {
        Map<String, Double> scores = new LinkedHashMap<>();
        List<String> issues = new ArrayList<>();

        scores.put("accuracy", qualityMetrics.accuracy(output, expected));
        scores.put("relevance", qualityMetrics.relevance(output, input));
        scores.put("safety", safetyMetrics.safetyScore(output));
        scores.put("hallucination", 1.0 - hallucinationDetector.hallucinationRisk(output));

        issues.addAll(safetyMetrics.detectIssues(output));
        issues.addAll(hallucinationDetector.detectMarkers(output));

        return new EvaluationResult(scores, "Automated evaluation", issues);
    }

        public EvaluationCriteria getCriteria() {
        return EvaluationCriteria.defaultCriteria();
    }
}
