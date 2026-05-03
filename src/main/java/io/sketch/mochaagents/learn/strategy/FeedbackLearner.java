package io.sketch.mochaagents.learn.strategy;

import io.sketch.mochaagents.learn.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 反馈学习器 — 基于人类或自动反馈信号调整行为，支持正向强化与负向纠偏.
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 * @author lanxia39@163.com
 */
public class FeedbackLearner<I, O> implements Learner<I, O> {

    private final List<Experience<I, O>> history;
    private LearningStrategy strategy;
    private double weight = 0.5;

    public FeedbackLearner(LearningStrategy strategy) {
        this.history = new ArrayList<>();
        this.strategy = strategy;
    }

    public FeedbackLearner() {
        this(LearningStrategy.reinforcement(0.05));
    }

    @Override
    public void learn(Experience<I, O> experience) {
        history.add(experience);
        weight = strategy.updateWeight(weight, experience, new ArrayList<>(history));
    }

    @Override
    public void learnBatch(List<Experience<I, O>> experiences) {
        experiences.forEach(this::learn);
    }

    @Override
    public O infer(I input) {
        // 基于加权历史选择最佳输出
        return history.stream()
                .filter(e -> e.isPositive())
                .max(Comparator.<Experience<I, O>>comparingDouble(e -> e.reward()))
                .map(Experience::output)
                .orElse(null);
    }

    @Override
    public LearningStrategy getStrategy() { return strategy; }

    @Override
    public void setStrategy(LearningStrategy strategy) { this.strategy = strategy; }

    @Override
    public int experienceCount() { return history.size(); }

    public double getWeight() { return weight; }

    /** 获取最近的正向反馈经验 */
    public List<Experience<I, O>> positiveFeedback(int limit) {
        return history.stream()
                .filter(Experience::isPositive)
                .sorted(Comparator.<Experience<I, O>, Instant>comparing(e -> e.timestamp()).reversed())
                .limit(limit)
                .toList();
    }

    /** 获取最近的负向反馈经验 */
    public List<Experience<I, O>> negativeFeedback(int limit) {
        return history.stream()
                .filter(Experience::isNegative)
                .sorted(Comparator.<Experience<I, O>, Instant>comparing(e -> e.timestamp()).reversed())
                .limit(limit)
                .toList();
    }
}
