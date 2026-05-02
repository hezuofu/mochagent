package io.sketch.mochaagents.evaluation.judge;

import io.sketch.mochaagents.evaluation.EvaluationResult;
import java.util.List;
import java.util.Map;

/**
 * 人类评判 — 提供人工评估接口和反馈收集.
 */
public class HumanJudge {

    private final List<EvaluationResult> judgments = new java.util.ArrayList<>();

    /** 记录人类评判 */
    public EvaluationResult recordJudgment(Map<String, Double> scores, String comment) {
        EvaluationResult result = new EvaluationResult(scores, comment, List.of());
        judgments.add(result);
        return result;
    }

    /** 获取所有评判 */
    public List<EvaluationResult> getJudgments() {
        return List.copyOf(judgments);
    }

    /** 获取平均分 */
    public double averageScore() {
        return judgments.stream()
                .mapToDouble(EvaluationResult::overallScore)
                .average()
                .orElse(0.0);
    }
}
