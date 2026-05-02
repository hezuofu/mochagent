package io.sketch.mochaagents.reasoning;

/**
 * 推理器接口 — 统一推理入口，策略可插拔.
 */
public interface Reasoner {

    ReasoningChain reason(String question);

    void setStrategy(ReasoningStrategy strategy);

    ReasoningStrategy getStrategy();
}
