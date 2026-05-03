package io.sketch.mochaagents.evaluation.judge;

import io.sketch.mochaagents.evaluation.EvaluationCriteria;
import io.sketch.mochaagents.evaluation.EvaluationResult;
import io.sketch.mochaagents.evaluation.Evaluator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人类评判 — 提供人工评估接口，记录评分并返回统计.
 * @author lanxia39@163.com
 */
public class HumanJudge implements Evaluator {

    private final List<EvaluationResult> judgments = new ArrayList<>();

    @Override
    public EvaluationResult evaluate(String input, String output, String expected) {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("human_overall", averageScore() > 0 ? averageScore() : 0.7);
        return new EvaluationResult(scores, "Human review pending", List.of());
    }

    @Override
    public EvaluationCriteria getCriteria() {
        return EvaluationCriteria.defaultCriteria();
    }

    /** 记录人工评分 */
    public EvaluationResult recordJudgment(Map<String, Double> scores, String comment) {
        EvaluationResult result = new EvaluationResult(scores, comment, List.of());
        judgments.add(result);
        return result;
    }

    /** 获取所有评判记录 */
    public List<EvaluationResult> getJudgments() {
        return List.copyOf(judgments);
    }

    /** 获取历史平均分 */
    public double averageScore() {
        return judgments.stream()
                .mapToDouble(EvaluationResult::overallScore)
                .average()
                .orElse(0.0);
    }

    /** 是否有历史评判记录 */
    public boolean hasHistory() {
        return !judgments.isEmpty();
    }
}
