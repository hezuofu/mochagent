// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.memory;

import io.sketch.mochaagents.agent.loop.step.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks the agent's step-by-step trajectory within a conversation.
 *
 * <p>Extracted from MemoryManager following the hermes-agent pattern where
 * trajectory/step recording is separate from memory management.
 * This component owns the step list, step counting, truncation, and
 * snapshot generation — all the "what happened" tracking that feeds
 * into both the prompt builder and the memory persistence layer.
 *
 * <p>Corresponds to hermes-agent's {@code TrajectoryRecorder} (Python)
 * and Claude Code's message list in {@code QueryEngine.mutableMessages}.
 *
 * @author lanxia39@163.com
 */
public class StepTracker {

    private volatile String systemPrompt;
    private final List<MemoryStep> steps = new CopyOnWriteArrayList<>();

    // ── Step management ──

    public void remember(MemoryStep step) {
        steps.add(step);
    }

    public List<MemoryStep> steps() {
        return Collections.unmodifiableList(steps);
    }

    public int stepCount() {
        return steps.size();
    }

    /** Reset all steps and set a new system prompt. */
    public void reset(String systemPrompt) {
        steps.clear();
        this.systemPrompt = systemPrompt;
    }

    /** Replace a step at the given index (used by compaction operations). */
    public void replaceStep(int index, MemoryStep step) {
        if (index >= 0 && index < steps.size()) {
            steps.set(index, step);
        }
    }

    // ── Convenience append helpers ──

    public void appendSystemPrompt(String prompt) {
        steps.add(ContentStep.systemPrompt(prompt));
    }

    public void appendTask(String task) {
        steps.add(ContentStep.task(task));
    }

    public void appendPlanning(String plan, String observation, int inputTokens, int outputTokens) {
        steps.add(new PlanningStep(plan, observation, inputTokens, outputTokens));
    }

    public void appendAction(ActionStep action) {
        steps.add(action);
    }

    public void appendFinalAnswer(Object result) {
        steps.add(ContentStep.finalAnswer(result));
    }

    // ── Turn-level restore ──

    /** Truncate history to the given step number (inclusive). Returns count of removed steps. */
    public int truncateToStep(int stepNumber) {
        int removed = 0;
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i) instanceof ActionStep as && as.stepNumber() > stepNumber) {
                steps.remove(i);
                removed++;
            } else if (steps.get(i) instanceof ContentStep cs && i > 0
                    && steps.get(i - 1) instanceof ActionStep a
                    && a.stepNumber() > stepNumber) {
                steps.remove(i);
                removed++;
            }
        }
        return removed;
    }

    /** Summarize turns for display. */
    public List<String> turnSummaries() {
        List<String> turns = new ArrayList<>();
        for (var step : steps) {
            if (step instanceof ActionStep as) {
                String action = as.action() != null ? as.action() : "";
                if (action.length() > 50) {
                    action = action.substring(0, 50) + "...";
                }
                String summary = "Step " + as.stepNumber() + ": "
                        + (as.isFinalAnswer() ? "✓ " : "→ ") + action;
                if (as.hasError()) {
                    summary += " ⚡";
                }
                turns.add(summary);
            }
        }
        return turns;
    }

    /** Find the maximum step number. */
    public int maxStepNumber() {
        int max = 0;
        for (var step : steps) {
            if (step instanceof ActionStep as && as.stepNumber() > max) {
                max = as.stepNumber();
            }
        }
        return max;
    }

    public boolean hasFinalAnswer() {
        return steps.stream().anyMatch(s -> s instanceof ContentStep cs && cs.isFinalAnswer());
    }

    // ── System prompt ──

    public String systemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    // ── Snapshot: MemoryStep → MemoryRecord ──

    /** Convert current steps into persistent memory records for the store. */
    public List<MemoryRecord> snapshot() {
        List<MemoryRecord> entries = new ArrayList<>();
        for (MemoryStep s : steps) {
            if (s instanceof ActionStep act) {
                entries.add(MemoryEntry.builder()
                        .content("[Step " + act.stepNumber() + "] "
                                + (act.observation() != null ? act.observation() : act.modelOutput()))
                        .type(MemoryRecord.TYPE_EPISODIC)
                        .importance(act.hasError() ? 0.2 : 0.6)
                        .build());
            } else if (s instanceof ContentStep cs && cs.isFinalAnswer()) {
                entries.add(MemoryEntry.builder()
                        .content("Task result: " + cs.payload())
                        .type(MemoryRecord.TYPE_SEMANTIC)
                        .concepts(Set.of("final", "output"))
                        .build());
            }
        }
        return entries;
    }
}
