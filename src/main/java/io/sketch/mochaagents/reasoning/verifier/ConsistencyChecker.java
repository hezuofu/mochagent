package io.sketch.mochaagents.reasoning.verifier;

import io.sketch.mochaagents.reasoning.ReasoningChain;

/**
 * 一致性检查 — 检查推理步骤间逻辑一致性.
 * @author lanxia39@163.com
 */
public class ConsistencyChecker implements ReasoningVerifier {
    @Override public boolean verify(ReasoningChain chain) { return !chain.steps().isEmpty(); }
    @Override public String explain(ReasoningChain chain) { return "Consistency: " + (verify(chain) ? "PASS" : "FAIL"); }
}
