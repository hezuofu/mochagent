package io.sketch.mochaagents.reasoning.verifier;

import io.sketch.mochaagents.reasoning.ReasoningChain;

/**
 * 推理验证器接口 — 验证推理链的正确性.
 */
public interface ReasoningVerifier {
    boolean verify(ReasoningChain chain);
    String explain(ReasoningChain chain);
}
