// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.reasoning;

import io.sketch.mochaagents.model.Model;
import io.sketch.mochaagents.model.ModelRequest;
import io.sketch.mochaagents.model.ModelResponse;
import io.sketch.mochaagents.reasoning.ChainOfThought;
import io.sketch.mochaagents.reasoning.GraphOfThought;
import io.sketch.mochaagents.reasoning.ProgramOfThought;
import io.sketch.mochaagents.reasoning.TreeOfThought;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReasoningStrategyTest {

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

    @Test
    void chainOfThoughtParsesSteps() {
        Model llm = mockLlm("Step 1: Analyze the problem\nConfidence: 0.9\nStep 2: Solve\nConfidence: 0.85");
        ChainOfThought cot = new ChainOfThought(llm);
        ReasoningChain chain = cot.reason("test");

        assertTrue(chain.steps().size() >= 1);
        assertTrue(chain.averageConfidence() > 0);
    }

    @Test
    void chainOfThoughtFallbackWhenEmpty() {
        Model llm = mockLlm("No steps here");
        ChainOfThought cot = new ChainOfThought(llm);
        ReasoningChain chain = cot.reason("test");

        assertFalse(chain.steps().isEmpty());
    }

    @Test
    void treeOfThoughtGeneratesBranches() {
        Model llm = mockLlm("Branch 1: Option A\nConclusion: Good\nScore: 0.9\nBranch 2: Option B\nConclusion: OK\nScore: 0.7");
        TreeOfThought tot = new TreeOfThought(llm, 2, 1);
        ReasoningChain chain = tot.reason("test");

        assertTrue(chain.steps().size() >= 2);
    }

    @Test
    void programOfThoughtExtractsCode() {
        Model llm = mockLlm("```python\nprint(42)\n```\nExplanation: It works");
        ProgramOfThought pot = new ProgramOfThought(llm);
        ReasoningChain chain = pot.reason("compute 42");

        assertTrue(chain.steps().size() >= 2);
    }

    @Test
    void graphOfThoughtParsesNodes() {
        Model llm = mockLlm("ID: N1\nThought: Root idea\nDependsOn: none\nConfidence: 0.9\n\nID: N2\nThought: Child idea\nDependsOn: N1\nConfidence: 0.8");
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
