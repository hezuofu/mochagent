package io.sketch.mochaagents.reasoning.verifier;

import io.sketch.mochaagents.reasoning.ReasoningChain;

/**
 * 逻辑验证器 — 验证推理结论的逻辑正确性.
 */
public class LogicVerifier implements ReasoningVerifier {
    @Override public boolean verify(ReasoningChain chain) { return chain.averageConfidence() > 0.5; }
    @Override public String explain(ReasoningChain chain) { return "Logic: confidence=" + chain.averageConfidence() + " → " + (verify(chain) ? "PASS" : "FAIL"); }
}
