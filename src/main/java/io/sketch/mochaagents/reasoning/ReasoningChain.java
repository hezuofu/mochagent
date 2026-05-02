package io.sketch.mochaagents.reasoning;

import java.util.ArrayList;
import java.util.List;

/**
 * 推理链 — 一组推理步骤的序列.
 */
public class ReasoningChain {

    private final List<ReasoningStep> steps = new ArrayList<>();

    public void add(ReasoningStep step) { steps.add(step); }

    public List<ReasoningStep> steps() { return List.copyOf(steps); }

    public String summarize() {
        StringBuilder sb = new StringBuilder();
        for (ReasoningStep s : steps) {
            sb.append("Step ").append(s.index()).append(": ")
              .append(s.thought()).append(" → ").append(s.conclusion()).append("\n");
        }
        return sb.toString();
    }

    public double averageConfidence() {
        return steps.stream().mapToDouble(ReasoningStep::confidence).average().orElse(0);
    }

    public static ReasoningChain empty() { return new ReasoningChain(); }
}
