// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.reasoning;

/**
 * Minimal reasoning — analyze a question and return a reasoning chain.
 */
@FunctionalInterface
public interface Reasoner {

    ReasoningChain reason(String question);
}
