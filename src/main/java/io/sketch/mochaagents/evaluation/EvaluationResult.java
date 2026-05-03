package io.sketch.mochaagents.evaluation;

import java.util.*;

/**
 * 评估结果 — 多维度评估得分与综合评级.
 * @author lanxia39@163.com
 */
public class EvaluationResult {

    private final Map<String, Double> scores;
    private final double overallScore;
    private final String grade;
    private final List<String> issues;
    private final String summary;

    public EvaluationResult(Map<String, Double> scores, String summary, List<String> issues) {
        this.scores = Map.copyOf(scores);
        this.overallScore = scores.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        this.grade = computeGrade(overallScore);
        this.issues = List.copyOf(issues);
        this.summary = summary;
    }

    public Map<String, Double> scores() { return scores; }
    public double overallScore() { return overallScore; }
    public String grade() { return grade; }
    public List<String> issues() { return issues; }
    public String summary() { return summary; }
    public boolean passed() { return overallScore >= 0.6; }

    private static String computeGrade(double score) {
        if (score >= 0.9) return "A";
        if (score >= 0.8) return "B";
        if (score >= 0.7) return "C";
        if (score >= 0.6) return "D";
        return "F";
    }
}
