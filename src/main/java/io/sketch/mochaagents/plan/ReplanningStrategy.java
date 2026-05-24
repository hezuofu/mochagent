// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.plan;

import io.sketch.mochaagents.model.Model;
import io.sketch.mochaagents.model.ModelRequest;
import io.sketch.mochaagents.plan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 重规划策略 — 用 Model 分析失败并生成替代方案.
 * @author lanxia39@163.com
 */
public class ReplanningStrategy implements PlanningStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReplanningStrategy.class);
    private final Model model;

    public ReplanningStrategy(Model model) { this.model = model; }

    @Override
    @SuppressWarnings("unchecked")
    public Plan<?> plan(PlanningRequest<?> request) {
        Object goalObj = request.goal();
        String goal = goalObj != null ? goalObj.toString() : "";
        String context = request.context() != null ? request.context() : "";
        int maxSteps = request.maxSteps() > 0 ? request.maxSteps() : 5;

        String prompt;
        if (context != null && !context.isEmpty()) {
            prompt = String.format("""
                    Previous plan failed. Analyze why and create new plan.
                    Goal: %s
                    Failure: %s
                    Max steps: %d
                    Output: Step N: <description>""", goal, context, maxSteps);
        } else {
            prompt = String.format("""
                    Create a plan.
                    Goal: %s
                    Max steps: %d
                    Output: Step N: <description>""", goal, maxSteps);
        }

        String response = model.complete(ModelRequest.builder()
                .addMessage("user", prompt).maxTokens(1024).temperature(0.4).build()).content();

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
        if (plan.getSteps().isEmpty()) {
            plan.addStep(PlanStep.builder()
                    .stepId("step-1").description("Retry: " + goal).build());
        }

        log.debug("ReplanningStrategy: {} steps", plan.getSteps().size());
        return plan;
    }
}
