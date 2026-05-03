package io.sketch.mochaagents.learn.strategy;

import io.sketch.mochaagents.learn.*;
import java.util.Comparator;

/**
 * 少样本学习器 — 从少量示例中快速学习模式，用于 prompt 示例选择.
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 * @author lanxia39@163.com
 */
public class FewShotLearner<I, O> implements Learner<I, O> {

    private final int maxExamples;
    private final java.util.List<Experience<I, O>> examples;
    private LearningStrategy strategy;

    public FewShotLearner(int maxExamples, LearningStrategy strategy) {
        this.maxExamples = maxExamples;
        this.examples = new java.util.ArrayList<>();
        this.strategy = strategy;
    }

    public FewShotLearner(int maxExamples) {
        this(maxExamples, LearningStrategy.weightedRecent(0.1, 0.05));
    }

    @Override
    public void learn(Experience<I, O> experience) {
        examples.add(experience);
        if (examples.size() > maxExamples) {
            // 淘汰最低奖励的经验
            examples.stream()
                    .min(Comparator.<Experience<I, O>>comparingDouble(e -> e.reward()))
                    .ifPresent(examples::remove);
        }
    }

    @Override
    public void learnBatch(java.util.List<Experience<I, O>> experiences) {
        experiences.forEach(this::learn);
    }

    @Override
    public O infer(I input) {
        // 返回最相似输入的最佳经验输出
        return examples.stream()
                .max(Comparator.<Experience<I, O>>comparingDouble(e -> e.reward()))
                .map(Experience::output)
                .orElse(null);
    }

    @Override
    public LearningStrategy getStrategy() { return strategy; }

    @Override
    public void setStrategy(LearningStrategy strategy) { this.strategy = strategy; }

    @Override
    public int experienceCount() { return examples.size(); }

    /** 获取前 N 个最佳示例（用于 prompt 构建） */
    public java.util.List<Experience<I, O>> topExamples(int n) {
        return examples.stream()
                .sorted(Comparator.<Experience<I, O>>comparingDouble(e -> e.reward()).reversed())
                .limit(n)
                .toList();
    }
}
