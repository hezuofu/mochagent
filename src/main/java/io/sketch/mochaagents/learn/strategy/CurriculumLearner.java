package io.sketch.mochaagents.learn.strategy;

import io.sketch.mochaagents.learn.*;
import java.util.*;

/**
 * 课程学习器 — 按照由易到难的课程顺序逐步学习，逐步提升能力.
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public class CurriculumLearner<I, O> implements Learner<I, O> {

    private final List<Experience<I, O>> completed;
    private final PriorityQueue<CurriculumItem<I>> curriculum;
    private LearningStrategy strategy;
    private int currentLevel;

    public CurriculumLearner(LearningStrategy strategy) {
        this.completed = new ArrayList<>();
        this.curriculum = new PriorityQueue<>(Comparator.comparingDouble(CurriculumItem::difficulty));
        this.strategy = strategy;
        this.currentLevel = 0;
    }

    @Override
    public void learn(Experience<I, O> experience) {
        completed.add(experience);
        currentLevel++;
    }

    @Override
    public void learnBatch(List<Experience<I, O>> experiences) {
        experiences.forEach(this::learn);
    }

    @Override
    public O infer(I input) {
        // 返回同难度级别的最佳经验输出
        return completed.stream()
                .filter(e -> estimateDifficulty(e) <= currentLevel * 0.3 + 0.1)
                .max(Comparator.<Experience<I, O>>comparingDouble(e -> e.reward()))
                .map(Experience::output)
                .orElse(null);
    }

    @Override
    public LearningStrategy getStrategy() { return strategy; }
    @Override
    public void setStrategy(LearningStrategy strategy) { this.strategy = strategy; }
    @Override
    public int experienceCount() { return completed.size(); }

    /** 添加课程项 */
    public void addToCurriculum(I input, double difficulty) {
        curriculum.add(new CurriculumItem<>(input, difficulty));
    }

    /** 获取下一个待学习的课程项 */
    public Optional<CurriculumItem<I>> nextLesson() {
        return Optional.ofNullable(curriculum.poll());
    }

    /** 当前学习级别 */
    public int currentLevel() { return currentLevel; }

    private double estimateDifficulty(Experience<?, ?> exp) {
        return exp.isNegative() ? 0.8 : 0.2;
    }

    /** 课程项 */
    public record CurriculumItem<T>(T input, double difficulty) {}
}
