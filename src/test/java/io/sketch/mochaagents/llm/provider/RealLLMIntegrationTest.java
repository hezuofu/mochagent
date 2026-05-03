package io.sketch.mochaagents.llm.provider;

import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.impl.CodeAgent;
import io.sketch.mochaagents.agent.impl.ToolCallingAgent;
import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolRegistry;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real LLM integration tests — requires API key to run.
 *
 * <p>Activate with: {@code mvn test -Dtest=RealLLMIntegrationTest}
 *
 * <p>Supported free-tier providers:
 * <ul>
 * <li><b>DeepSeek</b>: set env {@code DEEPSEEK_API_KEY} (free quota)</li>
 * <li><b>Groq</b>: set env {@code GROQ_API_KEY} (free tier, fast)</li>
 * <li><b>Google Gemini</b>: set env {@code GEMINI_API_KEY} (free quota)</li>
 * <li><b>Ollama</b>: local, no env needed</li>
 * </ul>
 * @author lanxia39@163.com
 */
@Disabled("Requires real LLM API key. Set DEEPSEEK_API_KEY or GROQ_API_KEY env var and remove @Disabled")
class RealLLMIntegrationTest {

    private static LLM resolveLlm() {
        String groqKey = System.getenv("GROQ_API_KEY");
        if (groqKey != null && !groqKey.isEmpty()) {
            return OpenAICompatibleLLM.compatibleBuilder()
                    .modelId("llama-3.3-70b-versatile")
                    .apiKey(groqKey)
                    .baseUrl("https://api.groq.com/openai/v1")
                    .build();
        }

        String dsKey = System.getenv("DEEPSEEK_API_KEY");
        if (dsKey != null && !dsKey.isEmpty()) {
            return OpenAICompatibleLLM.compatibleBuilder()
                    .modelId("deepseek-chat")
                    .apiKey(dsKey)
                    .baseUrl("https://api.deepseek.com/v1")
                    .build();
        }

        String geminiKey = System.getenv("GEMINI_API_KEY");
        if (geminiKey != null && !geminiKey.isEmpty()) {
            return OpenAICompatibleLLM.compatibleBuilder()
                    .modelId("gemini-2.0-flash")
                    .apiKey(geminiKey)
                    .baseUrl("https://generativelanguage.googleapis.com/v1beta/openai")
                    .build();
        }

        // Try Ollama local
        OpenAICompatibleLLM ollama = OpenAICompatibleLLM.forOllama("llama3.2");
        try {
            ollama.complete(io.sketch.mochaagents.llm.LLMRequest.builder()
                    .addMessage("user", "hi").maxTokens(5).build());
            return ollama;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "No LLM API key found. Set DEEPSEEK_API_KEY, GROQ_API_KEY, or GEMINI_API_KEY, or start Ollama locally.");
        }
    }

    // ============ Tests ============

    @Test
    void toolCallingAgentCompletesSimpleTask() {
        LLM llm = resolveLlm();
        ToolRegistry registry = new ToolRegistry();

        // Register a simple calculator tool
        registry.register(new Tool() {
            @Override public String getName() { return "calculator"; }
            @Override public String getDescription() { return "Evaluate a math expression. Input: expression (e.g. 2+3*4)"; }
            @Override public Map<String, ToolInput> getInputs() {
                return Map.of("expression", ToolInput.string("Math expression to evaluate"));
            }
            @Override public String getOutputType() { return "string"; }
            @Override public Object call(Map<String, Object> args) {
                String expr = (String) args.getOrDefault("expression", "0");
                try {
                    javax.script.ScriptEngineManager mgr = new javax.script.ScriptEngineManager();
                    javax.script.ScriptEngine engine = mgr.getEngineByName("JavaScript");
                    if (engine == null) engine = mgr.getEngineByName("graal.js");
                    if (engine != null) return engine.eval(expr).toString();
                } catch (Exception e) { /* fall through */ }
                // Simple eval fallback
                try { return String.valueOf(evalSimple(expr)); }
                catch (Exception e) { return "Error: " + e.getMessage(); }
            }
            @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
        });

        ToolCallingAgent agent = ToolCallingAgent.builder()
                .name("math-agent")
                .llm(llm)
                .toolRegistry(registry)
                .maxSteps(5)
                .build();

        String result = agent.run("What is 15 * 7 + 3?");

        assertNotNull(result);
        assertFalse(result.isEmpty());
        System.out.println("ToolCallingAgent result: " + result);
    }

    @Test
    void codeAgentWritesCodeAndProducesAnswer() {
        LLM llm = resolveLlm();

        CodeAgent agent = CodeAgent.builder()
                .name("python-agent")
                .llm(llm)
                .maxSteps(5)
                .build();

        String result = agent.run("Calculate the factorial of 5 using Python code.");

        assertNotNull(result);
        assertFalse(result.isEmpty());
        System.out.println("CodeAgent result: " + result);
    }

    @Test
    void agentWithAgentContextUsesHistory() {
        LLM llm = resolveLlm();

        ToolCallingAgent agent = ToolCallingAgent.builder()
                .name("context-agent")
                .llm(llm)
                .maxSteps(3)
                .build();

        AgentContext ctx = AgentContext.builder()
                .sessionId("test-001")
                .userId("tester")
                .userMessage("What is 2 + 2?")
                .metadata("instructions", "Answer in exactly one word: the number")
                .build();

        String result = agent.run(ctx);

        assertNotNull(result);
        System.out.println("ContextAgent result: " + result);
        assertTrue(result.contains("4") || result.contains("four"),
                "Expected answer to contain 4 or four, got: " + result);
    }

    // Helper: simple expression evaluator
    private static double evalSimple(String expr) {
        expr = expr.replaceAll("\\s+", "");
        // Handle multiplication first
        if (expr.contains("*")) {
            String[] parts = expr.split("\\*", 2);
            return evalSimple(parts[0]) * evalSimple(parts[1]);
        }
        if (expr.contains("+")) {
            String[] parts = expr.split("\\+", 2);
            return evalSimple(parts[0]) + evalSimple(parts[1]);
        }
        if (expr.contains("-") && expr.lastIndexOf('-') > 0) {
            String[] parts = expr.split("-", 2);
            return evalSimple(parts[0]) - evalSimple(parts[1]);
        }
        if (expr.contains("/")) {
            String[] parts = expr.split("/", 2);
            return evalSimple(parts[0]) / evalSimple(parts[1]);
        }
        return Double.parseDouble(expr);
    }
}
