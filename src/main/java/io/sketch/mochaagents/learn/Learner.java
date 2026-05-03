package io.sketch.mochaagents.learn;

/**
 * 学习器接口 — Agent 从经验中学习并改进行为的核心抽象.
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 * @author lanxia39@163.com
 */
public interface Learner<I, O> {

    /** 从单条经验中学习 */
    void learn(Experience<I, O> experience);

    /** 从多条经验中批量学习 */
    void learnBatch(java.util.List<Experience<I, O>> experiences);

    /** 基于学习经验做出预测/决策 */
    O infer(I input);

    /** 获取当前学习策略 */
    LearningStrategy getStrategy();

    /** 设定学习策略 */
    void setStrategy(LearningStrategy strategy);

    /** 获取已积累的经验数量 */
    int experienceCount();
}
