package io.sketch.mochaagents.evaluation;

import io.sketch.mochaagents.evaluation.judge.AutomatedJudge;
import io.sketch.mochaagents.evaluation.judge.LLMJudge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 组合评估器 — 链式调用多个 Judge 策略，合并评分为综合结果.
 *
 * <p>默认链: AutomatedJudge(指标评分) → LLMJudge(语义评判) → 加权合并.
 * <p>策略模式: 每个 Judge 是一个 {@link Evaluator} 策略，按注册顺序执行.
 * @author lanxia39@163.com
 */
public class CompositeEvaluator implements Evaluator {

    private static final Logger log = LoggerFactory.getLogger(CompositeEvaluator.class);

    private final List<Evaluator> judges;
    private final EvaluationCriteria criteria;
    private final double[] weights;

    private CompositeEvaluator(Builder builder) {
        this.judges = List.copyOf(builder.judges);
        this.criteria = builder.criteria != null ? builder.criteria : EvaluationCriteria.defaultCriteria();
        this.weights = builder.weights != null ? builder.weights
                : defaultWeights(builder.judges.size());
    }

    /** 使用 Automated + LLM 评估器的默认组合. */
    public static CompositeEvaluator defaults(LLMJudge llmJudge) {
        return builder()
                .addJudge(new AutomatedJudge())
                .addJudge(llmJudge)
                .build();
    }

    public static Builder builder() { return new Builder(); }

    @Override
    public EvaluationResult evaluate(String input, String output, String expected) {
        Map<String, Double> mergedScores = new LinkedHashMap<>();
        List<String> allIssues = new ArrayList<>();
        StringBuilder summary = new StringBuilder();

        for (int i = 0; i < judges.size(); i++) {
            Evaluator judge = judges.get(i);
            try {
                EvaluationResult result = judge.evaluate(input, output, expected);
                double weight = i < weights.length ? weights[i] : 1.0 / judges.size();

                for (var entry : result.scores().entrySet()) {
                    String key = judge.getClass().getSimpleName() + "." + entry.getKey();
                    mergedScores.merge(key, entry.getValue() * weight, Double::sum);
                }

                if (result.issues() != null) allIssues.addAll(result.issues());
                if (result.summary() != null) summary.append("[").append(result.summary()).append("] ");
            } catch (Exception e) {
                log.warn("Judge {} failed: {}", judge.getClass().getSimpleName(), e.getMessage());
            }
        }

        // Compute weighted overall score
        double overall = mergedScores.values().stream().mapToDouble(Double::doubleValue)
                .average().orElse(0.5);

        return new EvaluationResult(mergedScores,
                summary.length() > 0 ? summary.toString().trim() : "Composite evaluation",
                allIssues);
    }

    @Override
    public EvaluationCriteria getCriteria() { return criteria; }

    /** 注册的 Judge 数量 */
    public int judgeCount() { return judges.size(); }

    private static double[] defaultWeights(int count) {
        double[] w = new double[count];
        for (int i = 0; i < count; i++) w[i] = 1.0 / count;
        return w;
    }

    public static final class Builder {
        private final List<Evaluator> judges = new ArrayList<>();
        private EvaluationCriteria criteria;
        private double[] weights;

        public Builder addJudge(Evaluator judge) {
            judges.add(judge); return this;
        }

        public Builder criteria(EvaluationCriteria criteria) {
            this.criteria = criteria; return this;
        }

        public Builder weights(double... weights) {
            this.weights = weights; return this;
        }

        public CompositeEvaluator build() {
            if (judges.isEmpty()) throw new IllegalStateException("At least one judge required");
            return new CompositeEvaluator(this);
        }
    }
}
