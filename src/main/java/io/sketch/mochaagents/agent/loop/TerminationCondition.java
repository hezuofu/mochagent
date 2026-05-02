package io.sketch.mochaagents.agent.loop;

/**
 * 终止条件 — 函数式接口，判定循环是否应结束.
 */
@FunctionalInterface
public interface TerminationCondition {

    /**
     * 根据当前步骤结果判断是否终止.
     *
     * @param result 当前步骤结果
     * @return true 表示应终止循环
     */
    boolean shouldTerminate(StepResult result);

    /**
     * 默认终止条件: 达到最大步数.
     */
    static TerminationCondition maxSteps(int maxSteps) {
        return result -> result.stepNumber() >= maxSteps;
    }

    /**
     * 默认终止条件: 遇到错误立即终止.
     */
    static TerminationCondition onError() {
        return StepResult::hasError;
    }

    /**
     * 组合两个终止条件 (OR 逻辑).
     */
    default TerminationCondition or(TerminationCondition other) {
        return result -> this.shouldTerminate(result) || other.shouldTerminate(result);
    }

    /**
     * 组合两个终止条件 (AND 逻辑).
     */
    default TerminationCondition and(TerminationCondition other) {
        return result -> this.shouldTerminate(result) && other.shouldTerminate(result);
    }
}
