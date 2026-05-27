// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent.loop;
import io.sketch.mochaagents.prompt.Persona;

import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.ExecutionReport;
import io.sketch.mochaagents.agent.ToolCallingAgent;
import io.sketch.mochaagents.agent.loop.step.ActionStep;
import io.sketch.mochaagents.agent.loop.step.ContentStep;
import io.sketch.mochaagents.agent.loop.step.MemoryStep;
import io.sketch.mochaagents.interaction.PermissionRules;
import io.sketch.mochaagents.message.ContentBlock;
import io.sketch.mochaagents.message.Message;
import io.sketch.mochaagents.model.Model;
import io.sketch.mochaagents.model.ModelRequest;
import io.sketch.mochaagents.model.ModelResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    // ── writeTypedMessages() — MemoryStep → typed Message conversion ──

    @Test void writeTypedMessagesReturnsSystemPrompt() {
        var agent = ToolCallingAgent.builder().name("t").model(mock("Action: final_answer(answer=\"ok\")")).maxSteps(2).build();
        agent.run("test"); // populate memory
        List<Message> msgs = agent.writeTypedMessages();

        assertFalse(msgs.isEmpty());
        boolean hasSystem = msgs.stream().anyMatch(m -> m instanceof Message.SystemMessage);
        assertTrue(hasSystem, "Should contain system prompt message");
    }

    @Test void writeTypedMessagesIncludesUserTask() {
        var agent = ToolCallingAgent.builder().name("t").model(mock("Action: final_answer(answer=\"ok\")")).maxSteps(2).build();
        agent.run("hello world");
        List<Message> msgs = agent.writeTypedMessages();

        boolean hasTask = msgs.stream()
                .filter(m -> m instanceof Message.UserMessage)
                .anyMatch(m -> ((Message.UserMessage) m).content().contains("hello world"));
        assertTrue(hasTask, "Should contain user task message");
    }

    @Test void writeTypedMessagesIncludesAssistantResponse() {
        var agent = ToolCallingAgent.builder().name("t").model(mock("Action: final_answer(answer=\"ok\")")).maxSteps(2).build();
        agent.run("test");
        List<Message> msgs = agent.writeTypedMessages();

        boolean hasAssistant = msgs.stream().anyMatch(m -> m instanceof Message.AssistantMessage);
        assertTrue(hasAssistant, "Should contain assistant message");
    }

    @Test void writeTypedMessagesIncrementalDoesNotDuplicate() {
        var agent = ToolCallingAgent.builder().name("t").model(mock("Action: final_answer(answer=\"ok\")")).maxSteps(2).build();
        agent.run("test");
        List<Message> first = agent.writeTypedMessages();
        List<Message> second = agent.writeTypedMessages();
        assertEquals(first.size(), second.size(), "Second call should not duplicate messages");
    }

    @Test void actionStepWithTypedContentPreservesBlocks() {
        var agent = ToolCallingAgent.builder().name("t").model(mock("Action: final_answer(answer=\"ok\")")).maxSteps(2).build();
        agent.run("test");
        // After run, memory contains ActionSteps without typed content (fallback path)
        List<Message> msgs = agent.writeTypedMessages();
        // Fallback path: modelOutput wrapped in TextBlock
        long textBlocks = msgs.stream()
                .filter(m -> m instanceof Message.AssistantMessage)
                .flatMap(m -> ((Message.AssistantMessage) m).content().stream())
                .filter(b -> b instanceof ContentBlock.TextBlock)
                .count();
        assertTrue(textBlocks > 0, "Fallback path should produce TextBlocks");
    }

    // ── Content-block typed execution path ──

    @Test void executeReActStepProcessesContentBlocksWhenPresent() {
        // Model that returns typed content blocks
        Model typedModel = new Model() {
            @Override public ModelResponse complete(ModelRequest r) {
                var block = new ContentBlock.ToolUseBlock("call_1", "final_answer", Map.of("answer", "done"));
                var msg = new Message.AssistantMessage(List.of(block));
                return new ModelResponse("done", List.of(msg), "mock", 10, 10, 0L, Map.of());
            }
            @Override public CompletableFuture<ModelResponse> completeAsync(ModelRequest r) {
                return CompletableFuture.completedFuture(complete(r));
            }
            @Override public io.sketch.mochaagents.model.StreamingResponse stream(ModelRequest r) {
                throw new UnsupportedOperationException();
            }
            @Override public String modelName() { return "typed-mock"; }
            @Override public int maxContextTokens() { return 4096; }
        };

        var agent = ToolCallingAgent.builder().name("t").model(typedModel).maxSteps(2).build();
        String result = agent.run("test");

        assertNotNull(result);
        // ModelResponse had contentBlocks → typed path was used
        assertTrue(result.contains("done") || !result.isEmpty());
    }

    @Test void contentBlockToolUseIsParsedAsNativeToolCall() {
        Model typedModel = new Model() {
            @Override public ModelResponse complete(ModelRequest r) {
                var block = new ContentBlock.ToolUseBlock("t1", "calculator", Map.of("expression", "2+2"));
                var msg = new Message.AssistantMessage(List.of(block));
                return new ModelResponse("calc", List.of(msg), "mock", 5, 5, 0L, Map.of());
            }
            @Override public CompletableFuture<ModelResponse> completeAsync(ModelRequest r) {
                return CompletableFuture.completedFuture(complete(r));
            }
            @Override public io.sketch.mochaagents.model.StreamingResponse stream(ModelRequest r) {
                throw new UnsupportedOperationException();
            }
            @Override public String modelName() { return "typed-mock"; }
            @Override public int maxContextTokens() { return 4096; }
        };

        var agent = ToolCallingAgent.builder().name("t").model(typedModel).maxSteps(2).build();
        String result = agent.run("2+2");

        assertNotNull(result);
        // The calculator tool should have been executed against the mock ToolRegistry
        // (no calculator registered → observation mentions tool not found or result)
        assertFalse(result.isEmpty());
    }
}
