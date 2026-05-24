// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent.loop;
import io.sketch.mochaagents.prompt.Persona;

import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.ExecutionReport;
import io.sketch.mochaagents.agent.loop.ToolCallingAgent;
import io.sketch.mochaagents.interaction.PermissionRules;
import io.sketch.mochaagents.model.Model;
import io.sketch.mochaagents.model.ModelRequest;
import io.sketch.mochaagents.model.ModelResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ReActEdgeTest {

    private static Model mock(String response) {
        return new Model() {
            @Override public ModelResponse complete(ModelRequest r) { return ModelResponse.of(response); }
            @Override public CompletableFuture<ModelResponse> completeAsync(ModelRequest r) { return CompletableFuture.completedFuture(complete(r)); }
            @Override public io.sketch.mochaagents.model.StreamingResponse stream(ModelRequest r) { throw new UnsupportedOperationException(); }
            @Override public String modelName() { return "mock"; }
            @Override public int maxContextTokens() { return 4096; }
        };
    }

    @Test void emptyTaskReturnsResult() {
        var agent = ToolCallingAgent.builder().name("t").model(mock("Action: final_answer(answer=\"ok\")")).maxSteps(2).build();
        assertNotNull(agent.run(""));
    }

    @Test void nullAgentContextDoesNotThrow() {
        var agent = ToolCallingAgent.builder().name("t").model(mock("Action: final_answer(answer=\"ok\")")).maxSteps(2).build();
        assertDoesNotThrow(() -> agent.run("test"));
    }

    @Test void runAndReportContainsCostInfo() {
        var agent = ToolCallingAgent.builder().name("t").model(mock("Action: final_answer(answer=\"done\")")).maxSteps(2).build();
        ExecutionReport r = agent.runAndReport("test");
        assertNotNull(r.result());
        assertTrue(r.steps() >= 1);
        assertTrue(r.summary().contains("steps"));
    }

    @Test void personaGeneratesSystemPrompt() {
        String prompt = Persona.ENGINEER.buildSystemPrompt("- echo: Echo tool");
        assertTrue(prompt.contains("Software Engineer"));
        assertTrue(prompt.contains("SOLID"));
    }

    @Test void streamRunDoesNotThrow() {
        var agent = ToolCallingAgent.builder().name("t").model(mock("Action: final_answer(answer=\"ok\")")).maxSteps(2).build();
        StringBuilder sb = new StringBuilder();
        assertDoesNotThrow(() -> agent.runStreaming(AgentContext.of("test"), sb::append));
    }

    @Test void permissionWildcardMatching() {
        PermissionRules r = new PermissionRules().add("rm*", PermissionRules.Behavior.DENY, PermissionRules.Source.POLICY);
        assertEquals(PermissionRules.Behavior.DENY, r.resolve("rm"));
        assertEquals(PermissionRules.Behavior.DENY, r.resolve("rmdir"));
        assertEquals(PermissionRules.Behavior.ASK, r.resolve("echo"));
    }
}
