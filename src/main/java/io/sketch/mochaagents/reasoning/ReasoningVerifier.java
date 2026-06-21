// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.reasoning;

/**
 * 推理验证器接口 — 验证推理链的正确性.
 * @author lanxia39@163.com
 */
public interface ReasoningVerifier {

    boolean verify(ReasoningChain chain);

    String explain(ReasoningChain chain);
}
