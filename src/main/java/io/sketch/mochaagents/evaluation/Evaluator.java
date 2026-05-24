// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.evaluation;

/**
 * Minimal evaluation — assess agent output quality.
 */
@FunctionalInterface
public interface Evaluator {
    EvaluationResult evaluate(String input, String output, String expected);
}
