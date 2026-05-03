package io.sketch.mochaagents.plan;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import io.sketch.mochaagents.plan.decomposer.SemanticDecomposer;
import io.sketch.mochaagents.plan.strategy.AdaptivePlanner;
import io.sketch.mochaagents.plan.strategy.HierarchicalPlanner;
import io.sketch.mochaagents.plan.strategy.ReplanningStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanningStrategyTest {

    private static LLM mockLlm(String response) {
        return new LLM() {
            @Override public LLMResponse complete(LLMRequest req) {
                return new LLMResponse(response, "mock", 10, 5, 0, Map.of());
            }
            @Override public java.util.concurrent.CompletableFuture<LLMResponse> completeAsync(LLMRequest req) {
                return java.util.concurrent.CompletableFuture.completedFuture(complete(req));
            }
            @Override public io.sketch.mochaagents.llm.StreamingResponse stream(LLMRequest req) {
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
