package io.sketch.mochaagents.agent.impl;

import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.loop.step.ActionStep;
import io.sketch.mochaagents.agent.loop.step.MemoryStep;
import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentIntegrationTest {

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

    private ToolCallingAgent createAgent(LLM llm, Tool... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (Tool t : tools) registry.register(t);
        return ToolCallingAgent.builder().name("test-agent").llm(llm)
                .toolRegistry(registry).maxSteps(5).build();
    }

    // ============ Deep validation tests ============

    @Test
    void toolCallResultFlowsBackToLlmAsObservation() {
        var llm = new RecordingLLM();
        llm.addResponse("Thought: Call the echo tool.\nAction: echo(message=\"hello world\")");
        llm.addResponse("Thought: I got the echo result.\nAction: final_answer(answer=\"done\")");

        ToolCallingAgent agent = createAgent(llm, new EchoTool());
        String result = agent.run("test");

        // Verify: the SECOND LLM call includes the tool's ECHO output
        LLMRequest secondCall = llm.requests.get(1);
        String secondInput = extractAllContent(secondCall);
        assertTrue(secondInput.contains("ECHO: hello world"),
                "LLM should see tool output in observation. Got: " + secondInput);

        // Verify: final answer produced
        assertNotNull(result);
        assertTrue(agent.memory().hasFinalAnswer(), "Memory should have final answer step");
    }

    @Test
    void memoryCapturesFullExecutionTrace() {
        var llm = new RecordingLLM();
        llm.addResponse("Thought: Step one.\nAction: echo(message=\"first\")");
        llm.addResponse("Thought: Step two.\nAction: echo(message=\"second\")");
        llm.addResponse("Thought: Done.\nAction: final_answer(answer=\"complete\")");

        ToolCallingAgent agent = createAgent(llm, new EchoTool());
        agent.run("trace test");

        // Verify: at least 2 action steps (echo calls) plus final answer
        long actionCount = agent.memory().steps().stream()
                .filter(s -> s instanceof ActionStep).count();
        assertTrue(actionCount >= 2,
                "Should have at least 2 action steps, got " + actionCount
                + " steps: " + agent.memory().steps());

        // Verify: each action captured input and observation
        int withContent = 0;
        for (MemoryStep s : agent.memory().steps()) {
            if (s instanceof ActionStep as && as.modelOutput() != null
                    && !as.modelOutput().isEmpty()) {
                withContent++;
            }
        }
        assertTrue(withContent >= 2, "At least 2 actions should have model output");

        // Verify: final answer found
        assertTrue(agent.memory().hasFinalAnswer(), "Should have final answer step");
    }

    @Test
    void agentCanRecoverFromToolNotFound() {
        var llm = new RecordingLLM();
        // First: call a tool that doesn't exist
        llm.addResponse("Thought: Try unknown tool.\nAction: nonexistent_tool(input=\"test\")");
        // Second: after seeing the error observation, use the correct tool
        llm.addResponse("Thought: That tool not found. Use echo instead.\nAction: echo(message=\"plan b\")");
        // Third: final answer
        llm.addResponse("Thought: Got it.\nAction: final_answer(answer=\"recovered\")");

        ToolCallingAgent agent = createAgent(llm, new EchoTool());
        agent.run("recovery test");

        // Verify: the second LLM call's input contains the "Tool not found" error
        LLMRequest secondCall = llm.requests.get(1);
        String secondInput = extractAllContent(secondCall);
        assertTrue(secondInput.contains("Tool not found"),
                "LLM should see tool-not-found error. Got: " + secondInput);

        // Verify: agent recovered and completed
        assertTrue(agent.memory().hasFinalAnswer());
    }

    @Test
    void agentExceedsMaxStepsProducesFallback() {
        var llm = new RecordingLLM();
        // Never give final_answer — always keep trying
        for (int i = 0; i < 10; i++) {
            llm.addResponse("Thought: Let me think more.\nAction: echo(message=\"step " + i + "\")");
        }

        ToolCallingAgent agent = ToolCallingAgent.builder()
                .name("stuck-agent").llm(llm)
                .toolRegistry(new ToolRegistry())
                .maxSteps(3) // only 3 steps allowed
                .build();

        String result = agent.run("never-ending task");
        assertNotNull(result);
        // After exceeding max steps, should produce fallback
        assertTrue(agent.memory().steps().size() >= 4,
                "Should have task + 3 actions + final_answer");
    }

    @Test
    void codeAgentFullExecutionCycle() {
        var llm = new RecordingLLM();
        llm.addResponse("<code>\nx = 21 * 2\nfinal_answer(str(x))\n</code>");

        CodeAgent agent = CodeAgent.builder()
                .name("code-agent").llm(llm).maxSteps(3).build();

        String result = agent.run("compute 21*2");

        // Verify: code agent detected final_answer in code
        assertNotNull(result);
        assertTrue(agent.memory().hasFinalAnswer(),
                "CodeAgent should detect final_answer() in code block");

        // Verify: memory has action step for code execution
        boolean hasCodeAction = agent.memory().steps().stream()
                .filter(s -> s instanceof ActionStep as
                        && "python_interpreter".equals(as.action()))
                .findAny().isPresent();
        assertTrue(hasCodeAction, "Should have python_interpreter action");
    }

    @Test
    void agentContextConversationHistoryInjectedIntoMemory() {
        var llm = new RecordingLLM();
        llm.addResponse("Thought: I see history.\nAction: final_answer(answer=\"acknowledged\")");

        ToolCallingAgent agent = createAgent(llm);

        AgentContext ctx = AgentContext.builder()
                .sessionId("s1").userId("u1")
                .userMessage("current task")
                .conversationHistory("User: hello\nAssistant: hi there")
                .build();

        String result = agent.run(ctx);
        assertNotNull(result);

        // Verify: memory has more than just the task — conversation was injected
        assertTrue(agent.memory().steps().size() >= 2,
                "Memory should have task + conversation history steps");
    }

    // ============ Recording LLM helper ============

    private static final class RecordingLLM implements LLM {
        final java.util.List<LLMRequest> requests = new java.util.ArrayList<>();
        final java.util.List<String> responses = new java.util.ArrayList<>();
        int callIdx = 0;

        void addResponse(String r) { responses.add(r); }

        @Override
        public LLMResponse complete(LLMRequest req) {
            requests.add(req);
            String content = callIdx < responses.size() ? responses.get(callIdx) : "Action: final_answer(answer=\"fallback\")";
            callIdx++;
            return new LLMResponse(content, "mock", content.length() / 4, 10, 1, Map.of());
        }

        @Override public java.util.concurrent.CompletableFuture<LLMResponse> completeAsync(LLMRequest req) {
            return java.util.concurrent.CompletableFuture.completedFuture(complete(req));
        }
        @Override public io.sketch.mochaagents.llm.StreamingResponse stream(LLMRequest req) {
            throw new UnsupportedOperationException();
        }
        @Override public String modelName() { return "mock"; }
        @Override public int maxContextTokens() { return 4096; }
    }

    private static String extractAllContent(LLMRequest req) {
        StringBuilder sb = new StringBuilder();
        if (req.messages() != null) {
            for (var m : req.messages()) {
                sb.append(m.getOrDefault("content", "")).append("\n");
            }
        }
        sb.append(req.prompt() != null ? req.prompt() : "");
        return sb.toString();
    }
}
