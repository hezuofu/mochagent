package io.sketch.mochaagents.reasoning;

/**
 * 推理策略 — 函数式接口，定义推理算法.
 */
@FunctionalInterface
/** @author lanxia39@163.com */
public interface ReasoningStrategy {

    /**
     * 执行推理，生成推理链.
     *
     * @param question 待推理问题
     * @return 推理链
     */
    ReasoningChain reason(String question);
}
