package io.sketch.mochaagents.learn;

/**
 * 学习策略接口 — 定义 Agent 如何从经验中提取模式和调整行为.
 */
@FunctionalInterface
public interface LearningStrategy {

    /** 学习策略的权重更新 */
    double updateWeight(double currentWeight, Experience<?, ?> experience,
                        java.util.List<Experience<?, ?>> history);

    /** 默认强化学习策略 — 基于奖励信号调整 */
    static LearningStrategy reinforcement(double learningRate) {
        return (weight, exp, history) -> weight + learningRate * exp.reward();
    }

    /** 默认衰减策略 — 旧经验权重随时间降低 */
    static LearningStrategy decay(double decayRate) {
        return (weight, exp, history) -> weight * (1.0 - decayRate);
    }

    /** 默认复合策略 — 新经验影响更大 */
    static LearningStrategy weightedRecent(double learningRate, double recencyBias) {
        return (weight, exp, history) -> {
            if (history.isEmpty()) return weight;
            int recencyIndex = Math.max(0, history.size() - 1);
            double recencyFactor = Math.exp(-recencyBias * recencyIndex);
            return weight + learningRate * exp.reward() * recencyFactor;
        };
    }
}
