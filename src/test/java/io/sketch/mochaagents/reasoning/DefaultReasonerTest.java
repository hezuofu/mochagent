// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.reasoning;

import io.sketch.mochaagents.model.Model;
import io.sketch.mochaagents.model.ModelRequest;
import io.sketch.mochaagents.model.ModelResponse;
import io.sketch.mochaagents.reasoning.ChainOfThought;
import io.sketch.mochaagents.reasoning.TreeOfThought;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultReasonerTest {

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
    void defaultReasonerUsesChainOfThought() {
        DefaultReasoner reasoner = new DefaultReasoner(
                mockLlm("Step 1: Analyze\nConfidence: 0.9\nStep 2: Conclude\nConfidence: 0.85"));

        ReasoningChain chain = reasoner.reason("test question");
        assertFalse(chain.steps().isEmpty());
        assertTrue(chain.averageConfidence() > 0);
    }

    @Test
    void reasonerFallsBackOnLowConfidence() {
        Model badLlm = mockLlm("Step 1: Vague\nConfidence: 0.2");
        Model goodLlm = mockLlm("Step 1: Clear analysis\nConfidence: 0.9");

        DefaultReasoner reasoner = new DefaultReasoner(List.of(
                new ChainOfThought(badLlm),
                new ChainOfThought(goodLlm)));

        ReasoningChain chain = reasoner.reason("test");
        assertTrue(chain.averageConfidence() >= 0.5);
    }

    @Test
    void strategySwitchWorks() {
        DefaultReasoner reasoner = new DefaultReasoner(
                mockLlm("Step 1: Analysis\nConfidence: 0.8"));

        ReasoningStrategy original = reasoner.getStrategy();
        assertNotNull(original);

        reasoner.setStrategy(new ChainOfThought(mockLlm("Step 1: New\nConfidence: 0.9")));
        assertNotNull(reasoner.getStrategy());
        assertNotSame(original, reasoner.getStrategy());
    }

    @Test
    void multipleStrategiesRegistered() {
        Model llm = mockLlm("Step 1: Test\nConfidence: 0.7");
        DefaultReasoner reasoner = new DefaultReasoner(List.of(
                new ChainOfThought(llm), new TreeOfThought(llm)));

        assertEquals(2, reasoner.getStrategies().size());
    }
}
