package io.sketch.mochaagents.agent.react;

import java.util.function.Predicate;

/**
 * Termination predicates for ReAct loops.
 * <p>Replaces the TerminationCondition class with standard java.util.function.Predicate.
 * @author lanxia39@163.com
 */
public final class Termination {
    private Termination() {}

    public static Predicate<StepResult> maxSteps(int max) { return r -> r.stepNumber() >= max; }
    public static Predicate<StepResult> onError() { return StepResult::hasError; }
}
