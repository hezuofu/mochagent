package io.sketch.mochaagents.agent;

import java.util.List;

/**
 * Execution report — unified output for agent runs: result, steps, timing, cost.
 * @param result       final answer
 * @param steps        total ReAct steps taken
 * @param durationMs   wall-clock time
 * @param llmCost      estimated LLM cost (USD)
 * @param inputTokens  total input tokens consumed
 * @param outputTokens total output tokens produced
 * @param errors       any errors encountered
 * @param summary      human-readable one-line summary
 * @author lanxia39@163.com
 */
public record ExecutionReport(
        String result,
        int steps,
        long durationMs,
        double llmCost,
        long inputTokens,
        long outputTokens,
        List<String> errors,
        String summary
) {
    public boolean hasErrors() { return errors != null && !errors.isEmpty(); }

    @Override
    public String toString() {
        return summary + (hasErrors() ? " (errors: " + errors.size() + ")" : "");
    }
}
