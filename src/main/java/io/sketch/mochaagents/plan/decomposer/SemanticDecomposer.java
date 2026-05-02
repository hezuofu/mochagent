package io.sketch.mochaagents.plan.decomposer;

import io.sketch.mochaagents.plan.PlanStep;
import java.util.ArrayList;
import java.util.List;

/**
 * 语义分解器 — 基于语义理解将任务分解为子步骤.
 */
public class SemanticDecomposer implements TaskDecomposer {

    @Override
    public List<PlanStep> decompose(String task, int maxSteps) {
        List<PlanStep> steps = new ArrayList<>();
        for (int i = 0; i < Math.min(maxSteps, 3); i++) {
            steps.add(PlanStep.builder()
                    .stepId("step-" + (i + 1))
                    .description("Semantic step " + (i + 1) + " for: " + task)
                    .build());
        }
        return steps;
    }
}
