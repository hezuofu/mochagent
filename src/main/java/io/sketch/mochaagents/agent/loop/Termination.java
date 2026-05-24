// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent.loop;
import io.sketch.mochaagents.memory.MemoryManager;

import java.util.function.Predicate;

/**
 * Unified termination — max steps, final answer, error, and custom conditions.
 *
 * <p>Used by every AgentLoop strategy to decide when to stop:
 * <pre>{@code
 * var stop = new Termination(20).or(r -> r.output().contains("DONE"));
 * while (!stop.test(step, result, memory)) { ... }
 * }</pre>
  * @author lanxia39@163.com
 */
public final class Termination {
    private final int maxSteps;
    private final Predicate<StepResult> custom;

    public Termination(int maxSteps) { this(maxSteps, null); }
    public Termination(int maxSteps, Predicate<StepResult> custom) {
        this.maxSteps = maxSteps; this.custom = custom;
    }

    /** Add a custom condition (composes with AND). */
    public Termination or(Predicate<StepResult> condition) {
        return new Termination(maxSteps, custom == null ? condition : custom.or(condition));
    }

    /** Check all termination conditions. */
    public boolean test(int step, StepResult result, MemoryManager memory) {
        return step >= maxSteps
                || result.hasError()
                || (memory != null && memory.hasFinalAnswer())
                || (custom != null && custom.test(result));
    }

    // ── Static factories ──

    public static Predicate<StepResult> maxSteps(int max) { return r -> r.stepNumber() >= max; }
    public static Predicate<StepResult> onError() { return StepResult::hasError; }
}
