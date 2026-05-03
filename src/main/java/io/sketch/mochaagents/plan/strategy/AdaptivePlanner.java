package io.sketch.mochaagents.plan.strategy;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.plan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 自适应规划策略 — 用 LLM 生成计划并根据执行反馈动态调整.
 * @author lanxia39@163.com
 */
public class AdaptivePlanner implements PlanningStrategy {

    private static final Logger log = LoggerFactory.getLogger(AdaptivePlanner.class);
    private final LLM llm;
    private final List<ExecutionFeedback> history = new ArrayList<>();

    public AdaptivePlanner(LLM llm) { this.llm = llm; }

    @Override
    public Plan<?> plan(PlanningRequest<?> request) {
        Object goalObj = request.goal();
        String goal = goalObj != null ? goalObj.toString() : "";
        String ctx = request.context() != null ? request.context() : "";
        int steps = request.maxSteps() > 0 ? request.maxSteps() : 5;

        StringBuilder fb = new StringBuilder();
        if (!history.isEmpty()) {
            fb.append("\nPrevious attempts:\n");
            for (ExecutionFeedback f : history) {
                fb.append("- ").append(f.error() != null ? f.error() : "attempt")
                  .append(" (success=").append(f.isSuccessful()).append(")\n");
            }
            fb.append("Adapt your plan based on past failures.\n");
        }

        String prompt = String.format("""
                Create a step-by-step plan (max %d steps).
                Goal: %s
                Context: %s
                %s
                Output one step per line: Step N: <description>""",
                steps, goal, ctx, fb.toString());

        String response = llm.complete(LLMRequest.builder()
                .addMessage("user", prompt).maxTokens(1024).temperature(0.3).build()).content();

        @SuppressWarnings("unchecked")
        DefaultPlan<Object> plan = new DefaultPlan<>((PlanningRequest<Object>) request);
        for (String line : response.split("\n")) {
            line = line.trim();
            if (line.matches("(?i)Step\\s*\\d+[:：].*")) {
                String desc = line.replaceFirst("(?i)Step\\s*\\d+[:：]\\s*", "");
                plan.addStep(PlanStep.builder()
                        .stepId("step-" + (plan.getSteps().size() + 1))
                        .description(desc.trim()).build());
            }
        }

        log.debug("AdaptivePlanner generated {} steps", plan.getSteps().size());
        return plan;
    }

    public void recordFeedback(ExecutionFeedback feedback) { history.add(feedback); }
}
