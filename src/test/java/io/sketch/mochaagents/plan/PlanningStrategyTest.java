// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.plan;

import io.sketch.mochaagents.model.Model;
import io.sketch.mochaagents.model.ModelRequest;
import io.sketch.mochaagents.model.ModelResponse;
import io.sketch.mochaagents.plan.SemanticDecomposer;
import io.sketch.mochaagents.plan.AdaptivePlanner;
import io.sketch.mochaagents.plan.HierarchicalPlanner;
import io.sketch.mochaagents.plan.ReplanningStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanningStrategyTest {

    private static Model mockLlm(String response) {
        return new Model() {
            @Override public ModelResponse complete(ModelRequest req) {
                return new ModelResponse(response, "mock", 10, 5, 0, Map.of());
            }
            @Override public java.util.concurrent.CompletableFuture<ModelResponse> completeAsync(ModelRequest req) {
                return java.util.concurrent.CompletableFuture.completedFuture(complete(req));
            }
            @Override public io.sketch.mochaagents.model.StreamingResponse stream(ModelRequest req) {
                throw new UnsupportedOperationException();
            }
            @Override public String modelName() { return "mock"; }
            @Override public int maxContextTokens() { return 4096; }
        };
    }

    private static PlanningRequest<String> req(String goal) {
        return PlanningRequest.<String>builder().goal(goal).maxSteps(3).build();
    }

    // --- AdaptivePlanner ---

    @Test
    void adaptivePlannerGeneratesSteps() {
        AdaptivePlanner planner = new AdaptivePlanner(
                mockLlm("Step 1: Analyze requirements\nStep 2: Implement solution\nStep 3: Test"));

        Plan<?> plan = planner.plan(req("build feature X"));
        assertFalse(plan.getSteps().isEmpty());
        assertTrue(plan.getSteps().size() >= 1);
    }

    @Test
    void adaptivePlannerHandlesEmptyResponse() {
        AdaptivePlanner planner = new AdaptivePlanner(mockLlm("random text no steps here"));
        Plan<?> plan = planner.plan(req("task"));
        assertNotNull(plan);
    }

    // --- HierarchicalPlanner ---

    @Test
    void hierarchicalPlannerDecomposesGoal() {
        HierarchicalPlanner planner = new HierarchicalPlanner(
                mockLlm("- Sub-goal A\n- Sub-goal B\n- Sub-goal C"), 2);

        Plan<?> plan = planner.plan(req("complex goal"));
        assertFalse(plan.getSteps().isEmpty());
    }

    // --- ReplanningStrategy ---

    @Test
    void replanningStrategyGeneratesNewPlan() {
        ReplanningStrategy planner = new ReplanningStrategy(
                mockLlm("Step 1: Alternative approach\nStep 2: Validate"));

        Plan<?> plan = planner.plan(PlanningRequest.<String>builder()
                .goal("retry task").context("previous failure").maxSteps(2).build());
        assertFalse(plan.getSteps().isEmpty());
    }

    @Test
    void replanningStrategyFallbackWhenEmpty() {
        ReplanningStrategy planner = new ReplanningStrategy(mockLlm("no steps"));
        Plan<?> plan = planner.plan(req("task"));
        assertFalse(plan.getSteps().isEmpty()); // fallback step added
    }

    // --- SemanticDecomposer ---

    @Test
    void semanticDecomposerParsesDependencies() {
        SemanticDecomposer decomp = new SemanticDecomposer(
                mockLlm("- Read input  |  depends_on: none\n"
                      + "- Process data  |  depends_on: 1\n"
                      + "- Output result  |  depends_on: 2"));

        List<PlanStep> steps = decomp.decompose("data pipeline", 5);
        assertTrue(steps.size() >= 2);
    }

    @Test
    void semanticDecomposerFallbackWhenEmpty() {
        SemanticDecomposer decomp = new SemanticDecomposer(mockLlm("gibberish"));
        List<PlanStep> steps = decomp.decompose("task", 3);
        assertFalse(steps.isEmpty());
    }
}
