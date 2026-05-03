package io.sketch.mochaagents.plan.strategy;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.plan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 层次规划策略 — 用 LLM 将目标递归分解为子目标树.
 * @author lanxia39@163.com
 */
public class HierarchicalPlanner implements PlanningStrategy {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalPlanner.class);
    private final LLM llm;
    private final int maxDepth;

    public HierarchicalPlanner(LLM llm) { this(llm, 3); }
    public HierarchicalPlanner(LLM llm, int maxDepth) { this.llm = llm; this.maxDepth = maxDepth; }

    @Override
    @SuppressWarnings("unchecked")
    public Plan<?> plan(PlanningRequest<?> request) {
        DefaultPlan<Object> plan = new DefaultPlan<>((PlanningRequest<Object>) request);
        Object goalObj = request.goal();
        decomposeRecursive(goalObj != null ? goalObj.toString() : "",
                request.context() != null ? request.context() : "", 0, plan);
        log.debug("HierarchicalPlanner: {} steps, depth<={}", plan.getSteps().size(), maxDepth);
        return plan;
    }

    private void decomposeRecursive(String goal, String context, int depth, DefaultPlan<Object> plan) {
        if (depth >= maxDepth) return;

        String prompt = String.format("""
                Decompose into 2-4 sub-goals (level %d).
                Goal: %s
                Context: %s
                Output: - <sub-goal description>""", depth + 1, goal, context);

        String response = llm.complete(LLMRequest.builder()
                .addMessage("user", prompt).maxTokens(512).temperature(0.3).build()).content();

        for (String line : response.split("\n")) {
            line = line.trim();
            if (line.startsWith("- ") || line.matches("\\d+[.)]\\s+.*")) {
                String subGoal = line.replaceFirst("^[-\\d.)]+\\s*", "").trim();
                if (!subGoal.isEmpty()) {
                    plan.addStep(PlanStep.builder()
                            .stepId("step-" + (plan.getSteps().size() + 1))
                            .description("[L" + depth + "] " + subGoal).build());
                    decomposeRecursive(subGoal, "sub-goal of: " + goal, depth + 1, plan);
                }
            }
        }
        if (plan.getSteps().isEmpty()) {
            plan.addStep(PlanStep.builder()
                    .stepId("step-1").description(goal).build());
        }
    }
}
