package io.sketch.mochaagents.evaluation;

/**
 * Minimal evaluation — assess agent output quality.
 */
@FunctionalInterface
public interface Evaluator {
    EvaluationResult evaluate(String input, String output, String expected);
}
