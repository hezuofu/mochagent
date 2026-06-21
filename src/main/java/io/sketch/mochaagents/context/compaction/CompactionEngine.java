// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.context.compaction;

import io.sketch.mochaagents.agent.loop.step.ActionStep;
import io.sketch.mochaagents.agent.loop.step.MemoryStep;
import io.sketch.mochaagents.memory.MemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Multi-level compaction engine — Claude Code pattern.
 *
 * <p>Four levels, graduated by cost:
 * <ol>
 *   <li><b>SNIP</b> — remove old tool observations (free, no LLM)</li>
 *   <li><b>MICRO</b> — clear single tool result by name (free, no LLM)</li>
 *   <li><b>COLLAPSE</b> — truncate long outputs to max length (free, no LLM)</li>
 *   <li><b>AUTO</b> — LLM summarization via {@link AutoCompactor}</li>
 * </ol>
 *
 * <p>Usage from agent loop:
 * <pre>{@code
 * compactionEngine.snip(memory, keepLastN);
 * compactionEngine.collapse(memory, 2000);
 * }</pre>
 *
 * @author lanxia39@163.com
 */
public class CompactionEngine {

    private static final Logger log = LoggerFactory.getLogger(CompactionEngine.class);
    private static final int DEFAULT_MAX_OBSERVATION_LENGTH = 2000;
    // snip observations after N steps
    private static final int SNIP_THRESHOLD = 6;

    private final AutoCompactor autoCompactor;
    private int snipThreshold = SNIP_THRESHOLD;
    private int maxObservationLength = DEFAULT_MAX_OBSERVATION_LENGTH;

    public CompactionEngine(AutoCompactor autoCompactor) {
        this.autoCompactor = autoCompactor;
    }

    public CompactionEngine() {
        this.autoCompactor = null;
    }

    // ── Level 1: SNIP — remove old tool observations ──

    /**
     * Remove observation text from ActionSteps older than {@code keepLastN} steps.
     * Keeps model output (assistant thinking) but clears tool results to save context.
     * Free operation — no LLM call.
     *
     * @return number of steps snipped
     */
    public int snip(MemoryManager memory) {
        return snip(memory, snipThreshold);
    }

    public int snip(MemoryManager memory, int keepLastN) {
        List<MemoryStep> steps = memory.steps();
        int snipCount = 0;
        int cutoff = Math.max(0, steps.size() - keepLastN);

        for (int i = 0; i < cutoff; i++) {
            if (steps.get(i) instanceof ActionStep as && as.observation() != null && !as.observation().isEmpty()) {
                // Replace observation with a short marker, keep model output intact
                var snipped = new ActionStep(
                        as.stepNumber(), as.modelInput(), as.modelOutput(),
                        as.action(), "[snipped]", as.error(),
                        as.inputTokens(), as.outputTokens(), as.isFinalAnswer(),
                        as.assistantBlocks(), as.toolResultBlocks());
                memory.replaceStep(i, snipped);
                snipCount++;
            }
        }
        if (snipCount > 0) {
            log.debug("SNIP: cleared {} old observations", snipCount);
        }
        return snipCount;
    }

    // ── Level 2: MICRO — clear single tool result by name ──

    /**
     * Clear observation for a specific tool across all ActionSteps.
     * Used when a tool's output is known to be stale (e.g., file was re-read).
     *
     * @return number of steps cleared
     */
    public int microCompact(MemoryManager memory, String toolName) {
        List<MemoryStep> steps = memory.steps();
        int cleared = 0;

        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) instanceof ActionStep as
                    && as.action() != null && as.action().contains(toolName)
                    && as.observation() != null && !as.observation().isEmpty()
                    && !"[snipped]".equals(as.observation())) {
                var clearedStep = new ActionStep(
                        as.stepNumber(), as.modelInput(), as.modelOutput(),
                        as.action(), "[cleared:" + toolName + "]", as.error(),
                        as.inputTokens(), as.outputTokens(), as.isFinalAnswer(),
                        as.assistantBlocks(), as.toolResultBlocks());
                memory.replaceStep(i, clearedStep);
                cleared++;
            }
        }
        if (cleared > 0) {
            log.debug("MICRO: cleared {} results for tool '{}'", cleared, toolName);
        }
        return cleared;
    }

    // ── Level 3: COLLAPSE — truncate long outputs ──

    /**
     * Truncate observation text longer than {@code maxLen} characters.
     * Keeps first and last portion for context preservation.
     *
     * @return number of steps collapsed
     */
    public int collapse(MemoryManager memory) {
        return collapse(memory, maxObservationLength);
    }

    public int collapse(MemoryManager memory, int maxLen) {
        List<MemoryStep> steps = memory.steps();
        int collapsed = 0;

        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) instanceof ActionStep as
                    && as.observation() != null && as.observation().length() > maxLen
                    && !"[snipped]".equals(as.observation())) {
                String truncated = as.observation().substring(0, maxLen / 2)
                        + "\n... [collapsed " + (as.observation().length() - maxLen) + " chars] ...\n"
                        + as.observation().substring(as.observation().length() - maxLen / 2);
                var collapsedStep = new ActionStep(
                        as.stepNumber(), as.modelInput(), as.modelOutput(),
                        as.action(), truncated, as.error(),
                        as.inputTokens(), as.outputTokens(), as.isFinalAnswer(),
                        as.assistantBlocks(), as.toolResultBlocks());
                memory.replaceStep(i, collapsedStep);
                collapsed++;
            }
        }
        if (collapsed > 0) {
            log.debug("COLLAPSE: truncated {} long observations", collapsed);
        }
        return collapsed;
    }

    // ── Level 4: AUTO — LLM summarization ──

    /** Full LLM summarization via AutoCompactor. Returns null if unavailable or circuit open. */
    public String autoCompact(io.sketch.mochaagents.context.Context ctx) {
        if (autoCompactor == null) {
            return null;
        }
        return autoCompactor.compact(ctx);
    }

    // ── Combined: run all free levels in sequence ──

    /**
     * Run all non-LLM compaction levels: SNIP → COLLAPSE.
     * Call before building messages for the model.
     * @return total number of steps modified
     */
    public int preCompact(MemoryManager memory) {
        int count = 0;
        count += snip(memory);
        count += collapse(memory);
        return count;
    }

    // ── Configuration ──

    public CompactionEngine withSnipThreshold(int n) { this.snipThreshold = n; return this; }
    public CompactionEngine withMaxObservationLength(int n) { this.maxObservationLength = n; return this; }
    public boolean isCircuitOpen() { return autoCompactor != null && autoCompactor.isCircuitOpen(); }
    public void reset() {
        if (autoCompactor != null) {
            autoCompactor.reset();
        }
    }
}
