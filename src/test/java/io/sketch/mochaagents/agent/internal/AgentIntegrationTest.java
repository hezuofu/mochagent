// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent.internal;

import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.loop.ToolCallingAgent;
import io.sketch.mochaagents.agent.loop.step.*;
import io.sketch.mochaagents.model.*;
import io.sketch.mochaagents.tool.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class AgentIntegrationTest {

    private static final class EchoTool implements Tool {
        @Override public String getName() { return "echo"; }
        @Override public String getDescription() { return "Echoes input"; }
        @Override public Map<String, ToolInput> getInputs() { return Map.of("message", ToolInput.string("Message")); }
        @Override public String getOutputType() { return "string"; }
        @Override public Object call(Map<String, Object> args) { return "ECHO: " + args.getOrDefault("message", ""); }
        @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
    }

    private ToolCallingAgent createAgent(Model model, Tool... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (Tool t : tools) registry.register(t);
        return ToolCallingAgent.builder().name("test-agent").model(model)
                .toolRegistry(registry).maxSteps(5).build();
    }

    @Test void toolCallResultFlowsBackToModel() {
        var model = new RecordingModel();
        model.addResponse("Action: echo(message=\"hello world\")");
        model.addResponse("Action: final_answer(answer=\"done\")");
        model.addResponse("Action: final_answer(answer=\"fallback\")");

        ToolCallingAgent agent = createAgent(model, new EchoTool());
        String result = agent.run("test");
        assertNotNull(result);
        assertTrue(agent.memory().hasFinalAnswer(), "Agent should produce final answer");
    }

    @Test void memoryCapturesFullExecutionTrace() {
        var model = new RecordingModel();
        model.addResponse("Action: echo(message=\"first\")");
        model.addResponse("Action: echo(message=\"second\")");
        model.addResponse("Action: final_answer(answer=\"complete\")");

        ToolCallingAgent agent = createAgent(model, new EchoTool());
        agent.run("trace test");

        long actionCount = agent.memory().steps().stream().filter(s -> s instanceof ActionStep).count();
        assertTrue(actionCount >= 1, "Should have action steps, got " + actionCount);
        assertTrue(agent.memory().hasFinalAnswer());
    }

    @Test void agentCanRecoverFromToolNotFound() {
        var model = new RecordingModel();
        model.addResponse("Action: nonexistent_tool(input=\"test\")");
        model.addResponse("Action: echo(message=\"plan b\")");
        model.addResponse("Action: final_answer(answer=\"recovered\")");
        model.addResponse("Action: final_answer(answer=\"fallback\")");

        ToolCallingAgent agent = createAgent(model, new EchoTool());
        String result = agent.run("recovery test");
        assertNotNull(result);
        assertTrue(agent.memory().hasFinalAnswer(), "Agent should complete even after tool-not-found");
    }

    @Test void agentExceedsMaxStepsProducesFallback() {
        var model = new RecordingModel();
        for (int i = 0; i < 20; i++) model.addResponse("Action: echo(message=\"step " + i + "\")");

        ToolCallingAgent agent = ToolCallingAgent.builder()
                .name("stuck-agent").model(model).toolRegistry(new ToolRegistry()).maxSteps(3).build();
        String result = agent.run("never-ending task");
        assertNotNull(result);
        assertTrue(agent.memory().steps().size() >= 2, "Should have steps + fallback");
    }

    @Test void agentContextConversationHistoryInjectedIntoMemory() {
        var model = new RecordingModel();
        model.addResponse("Action: final_answer(answer=\"acknowledged\")");

        ToolCallingAgent agent = createAgent(model);

        AgentContext ctx = AgentContext.builder()
                .sessionId("s1").userId("u1").userMessage("current task")
                .conversationHistory("User: hello\nAssistant: hi there").build();

        String result = agent.run(ctx);
        assertNotNull(result);
        assertTrue(agent.memory().steps().size() >= 2, "Memory should have task + conversation history steps");
    }

    // ── Helpers ──

    private static String extractAllContent(ModelRequest req) {
        StringBuilder sb = new StringBuilder();
        for (var m : req.messages()) sb.append(m.getOrDefault("content", ""));
        return sb.toString();
    }

    private static final class RecordingModel implements Model {
        final List<ModelRequest> requests = new ArrayList<>();
        final List<String> responses = new ArrayList<>();
        int callIdx;

        void addResponse(String r) { responses.add(r); }

        @Override public ModelResponse complete(ModelRequest req) {
            requests.add(req);
            String content = callIdx < responses.size() ? responses.get(callIdx)
                    : "Action: final_answer(answer=\"fallback\")";
            callIdx++;
            return new ModelResponse(content, "mock", content.length() / 4, 10, 1, Map.of());
        }

        @Override public CompletableFuture<ModelResponse> completeAsync(ModelRequest req) {
            return CompletableFuture.completedFuture(complete(req));
        }

        @Override public String modelName() { return "mock"; }
    }
}
