package io.sketch.mochaagents.agent.impl;

import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests — end-to-end agent pipeline with mock LLM.
 * @author lanxia39@163.com
 */
class AgentIntegrationTest {

    // --- Test tool ---
    private static final class EchoTool implements Tool {
        @Override public String getName() { return "echo"; }
        @Override public String getDescription() { return "Echoes the input back"; }
        @Override public Map<String, ToolInput> getInputs() {
            return Map.of("message", ToolInput.string("Message to echo"));
        }
        @Override public String getOutputType() { return "string"; }
        @Override public Object call(Map<String, Object> args) {
            return "ECHO: " + args.getOrDefault("message", "");
        }
        @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
    }

    // --- Test fixtures ---

    private ToolCallingAgent createAgent(LLM llm, Tool... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (Tool t : tools) registry.register(t);

        return ToolCallingAgent.builder()
                .name("test-agent")
                .llm(llm)
                .toolRegistry(registry)
                .maxSteps(3)
                .build();
    }

    // --- Tests ---

    @Test
    void agentCompletesTaskWithFinalAnswer() {
        LLM llm = new LLM() {
            @Override
            public LLMResponse complete(LLMRequest req) {
                String lastMsg = lastUserContent(req);
                if (lastMsg != null && lastMsg.contains("Observation:")) {
                    return LLMResponse.of("Thought: Done.\nAction: final_answer(answer=\"completed\")");
                }
                return LLMResponse.of("Thought: Use echo.\nAction: echo(message=\"hello\")");
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

        ToolCallingAgent agent = createAgent(llm, new EchoTool());
        String result = agent.run("test task");
        assertNotNull(result);
    }

    @Test
    void agentRunsMultipleSteps() {
        LLM llm = new LLM() {
            private int callCount = 0;

            @Override
            public LLMResponse complete(LLMRequest req) {
                callCount++;
                String lastMsg = lastUserContent(req);
                if (callCount >= 3 || (lastMsg != null && lastMsg.contains("Observation:"))) {
                    return LLMResponse.of("Thought: Done.\nAction: final_answer(answer=\"step" + callCount + "\")");
                }
                return LLMResponse.of("Thought: Step " + callCount
                        + "\nAction: echo(message=\"step" + callCount + "\")");
            }

            @Override
            public java.util.concurrent.CompletableFuture<LLMResponse> completeAsync(LLMRequest req) {
                return java.util.concurrent.CompletableFuture.completedFuture(complete(req));
            }

            @Override
            public io.sketch.mochaagents.llm.StreamingResponse stream(LLMRequest req) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String modelName() { return "mock"; }

            @Override
            public int maxContextTokens() { return 4096; }
        };

        ToolCallingAgent agent = createAgent(llm, new EchoTool());
        String result = agent.run("multi-step task");

        assertNotNull(result);
        assertTrue(agent.memory().steps().size() >= 1);
    }

    @Test
    void agentWithAgentContextInjectsHistory() {
        LLM llm = new LLM() {
            @Override public LLMResponse complete(LLMRequest req) {
                return LLMResponse.of("Thought: Context seen.\nAction: final_answer(answer=\"context-aware\")");
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

        ToolCallingAgent agent = createAgent(llm);

        AgentContext ctx = AgentContext.builder()
                .sessionId("test-session")
                .userId("user-1")
                .userMessage("context-aware task")
                .conversationHistory("User: hello\nAssistant: hi there")
                .metadata("instructions", "Be concise")
                .build();

        String result = agent.run(ctx);
        assertNotNull(result);
    }

    @Test
    void codeAgentExtractsAndFinalAnswers() {
        LLM llm = new LLM() {
            @Override public LLMResponse complete(LLMRequest req) {
                return LLMResponse.of("```python\nresult = 42\nfinal_answer(\"42\")\n```");
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

        CodeAgent agent = CodeAgent.builder()
                .name("code-agent")
                .llm(llm)
                .maxSteps(3)
                .build();

        String result = agent.run("compute answer");
        assertNotNull(result);
    }

    @Test
    void agentWithMemoryKeepsSystemPrompt() {
        LLM llm = new LLM() {
            @Override public LLMResponse complete(LLMRequest req) {
                return LLMResponse.of("Action: final_answer(answer=\"done with prompt\")");
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

        ToolCallingAgent agent = createAgent(llm);
        agent.run("task");

        assertNotNull(agent.memory().systemPrompt());
        assertFalse(agent.memory().systemPrompt().isEmpty());
    }

    // --- Helpers ---

    private static String lastUserContent(LLMRequest req) {
        var msgs = req.messages();
        if (msgs != null) {
            for (int i = msgs.size() - 1; i >= 0; i--) {
                if ("user".equals(msgs.get(i).get("role"))) {
                    return msgs.get(i).get("content");
                }
            }
        }
        return req.prompt();
    }
}
