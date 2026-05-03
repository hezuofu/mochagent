package io.sketch.mochaagents.evaluation;

import io.sketch.mochaagents.evaluation.judge.AutomatedJudge;
import io.sketch.mochaagents.evaluation.judge.LLMJudge;
import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompositeEvaluatorTest {

    private static LLM mockLlm() {
        return new LLM() {
            @Override public LLMResponse complete(LLMRequest req) {
                return new LLMResponse("{\"accuracy\":0.9,\"relevance\":0.8,\"safety\":0.95}",
                        "mock", 10, 5, 0, Map.of());
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
