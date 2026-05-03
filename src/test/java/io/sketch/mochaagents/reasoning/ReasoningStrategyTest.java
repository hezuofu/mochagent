package io.sketch.mochaagents.reasoning;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import io.sketch.mochaagents.reasoning.strategy.ChainOfThought;
import io.sketch.mochaagents.reasoning.strategy.GraphOfThought;
import io.sketch.mochaagents.reasoning.strategy.ProgramOfThought;
import io.sketch.mochaagents.reasoning.strategy.TreeOfThought;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReasoningStrategyTest {

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

    @Test
    void chainOfThoughtParsesSteps() {
        LLM llm = mockLlm("Step 1: Analyze the problem\nConfidence: 0.9\nStep 2: Solve\nConfidence: 0.85");
        ChainOfThought cot = new ChainOfThought(llm);
        ReasoningChain chain = cot.reason("test");

        assertTrue(chain.steps().size() >= 1);
        assertTrue(chain.averageConfidence() > 0);
    }

    @Test
    void chainOfThoughtFallbackWhenEmpty() {
        LLM llm = mockLlm("No steps here");
        ChainOfThought cot = new ChainOfThought(llm);
        ReasoningChain chain = cot.reason("test");

        assertFalse(chain.steps().isEmpty());
    }

    @Test
    void treeOfThoughtGeneratesBranches() {
        LLM llm = mockLlm("Branch 1: Option A\nConclusion: Good\nScore: 0.9\nBranch 2: Option B\nConclusion: OK\nScore: 0.7");
        TreeOfThought tot = new TreeOfThought(llm, 2, 1);
        ReasoningChain chain = tot.reason("test");

        assertTrue(chain.steps().size() >= 2);
    }

    @Test
    void programOfThoughtExtractsCode() {
        LLM llm = mockLlm("```python\nprint(42)\n```\nExplanation: It works");
        ProgramOfThought pot = new ProgramOfThought(llm);
        ReasoningChain chain = pot.reason("compute 42");

        assertTrue(chain.steps().size() >= 2);
    }

    @Test
    void graphOfThoughtParsesNodes() {
        LLM llm = mockLlm("ID: N1\nThought: Root idea\nDependsOn: none\nConfidence: 0.9\n\nID: N2\nThought: Child idea\nDependsOn: N1\nConfidence: 0.8");
        GraphOfThought got = new GraphOfThought(llm);
        ReasoningChain chain = got.reason("test");

        assertTrue(chain.steps().size() >= 2);
    }

    @Test
    void reasoningChainEmptyChain() {
        ReasoningChain chain = ReasoningChain.empty();
        assertTrue(chain.steps().isEmpty());
        assertEquals(0, chain.averageConfidence(), 0.001);
    }

    @Test
    void reasoningStepProperties() {
        ReasoningStep step = new ReasoningStep(1, "thought", "conclusion", 0.8);
        assertEquals(1, step.index());
        assertEquals("thought", step.thought());
        assertEquals("conclusion", step.conclusion());
        assertEquals(0.8, step.confidence(), 0.001);
    }
}
