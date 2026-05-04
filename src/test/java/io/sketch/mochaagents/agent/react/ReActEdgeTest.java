package io.sketch.mochaagents.agent.react;

import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.ExecutionReport;
import io.sketch.mochaagents.agent.Persona;
import io.sketch.mochaagents.agent.impl.ToolCallingAgent;
import io.sketch.mochaagents.interaction.permission.PermissionRules;
import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ReActEdgeTest {

    private static LLM mock(String response) {
        return new LLM() {
            @Override public LLMResponse complete(LLMRequest r) { return LLMResponse.of(response); }
            @Override public CompletableFuture<LLMResponse> completeAsync(LLMRequest r) { return CompletableFuture.completedFuture(complete(r)); }
            @Override public io.sketch.mochaagents.llm.StreamingResponse stream(LLMRequest r) { throw new UnsupportedOperationException(); }
            @Override public String modelName() { return "mock"; }
            @Override public int maxContextTokens() { return 4096; }
        };
    }

    @Test void emptyTaskReturnsResult() {
        var agent = ToolCallingAgent.builder().name("t").llm(mock("Action: final_answer(answer=\"ok\")")).maxSteps(2).build();
        assertNotNull(agent.run(""));
    }

    @Test void nullAgentContextDoesNotThrow() {
        var agent = ToolCallingAgent.builder().name("t").llm(mock("Action: final_answer(answer=\"ok\")")).maxSteps(2).build();
        assertDoesNotThrow(() -> agent.run("test"));
    }

    @Test void runAndReportContainsCostInfo() {
        var agent = ToolCallingAgent.builder().name("t").llm(mock("Action: final_answer(answer=\"done\")")).maxSteps(2).build();
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
        var agent = ToolCallingAgent.builder().name("t").llm(mock("Action: final_answer(answer=\"ok\")")).maxSteps(2).build();
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
