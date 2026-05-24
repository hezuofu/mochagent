// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.evaluation;

import io.sketch.mochaagents.evaluation.AutomatedJudge;
import io.sketch.mochaagents.evaluation.LLMJudge;
import io.sketch.mochaagents.model.Model;
import io.sketch.mochaagents.model.ModelRequest;
import io.sketch.mochaagents.model.ModelResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompositeEvaluatorTest {

    private static Model mockLlm() {
        return new Model() {
            @Override public ModelResponse complete(ModelRequest req) {
                return new ModelResponse("{\"accuracy\":0.9,\"relevance\":0.8,\"safety\":0.95}",
                        "mock", 10, 5, 0, Map.of());
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

    @Test
    void defaultsCreatesWithTwoJudges() {
        CompositeEvaluator eval = CompositeEvaluator.defaults(new LLMJudge(mockLlm()));
        assertEquals(2, eval.judgeCount());
    }

    @Test
    void evaluateMergesScoresFromAllJudges() {
        CompositeEvaluator eval = CompositeEvaluator.builder()
                .addJudge(new AutomatedJudge())
                .addJudge(new LLMJudge(mockLlm()))
                .build();

        EvaluationResult result = eval.evaluate("test input", "test output", "expected");
        assertNotNull(result.scores());
        assertFalse(result.scores().isEmpty());
        assertTrue(result.overallScore() > 0);
    }

    @Test
    void builderThrowsWhenEmpty() {
        assertThrows(IllegalStateException.class,
                () -> CompositeEvaluator.builder().build());
    }

    @Test
    void weightedJudgesAffectOverallScore() {
        CompositeEvaluator eval = CompositeEvaluator.builder()
                .addJudge(new AutomatedJudge())
                .addJudge(new LLMJudge(mockLlm()))
                .weights(0.3, 0.7)
                .build();

        EvaluationResult result = eval.evaluate("input", "output", "expected");
        assertNotNull(result);
        assertTrue(result.overallScore() >= 0 && result.overallScore() <= 1.0);
    }
}
