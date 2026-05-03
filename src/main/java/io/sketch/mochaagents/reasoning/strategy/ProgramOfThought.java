package io.sketch.mochaagents.reasoning.strategy;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.reasoning.ReasoningChain;
import io.sketch.mochaagents.reasoning.ReasoningStep;
import io.sketch.mochaagents.reasoning.ReasoningStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

/**
 * Program of Thought — 让 LLM 生成可执行代码来推理，执行代码并基于输出得出结论.
 * @author lanxia39@163.com
 */
public class ProgramOfThought implements ReasoningStrategy {

    private static final Logger log = LoggerFactory.getLogger(ProgramOfThought.class);
    private final LLM llm;

    public ProgramOfThought(LLM llm) { this.llm = llm; }

    @Override
    public ReasoningChain reason(String question) {
        ReasoningChain chain = new ReasoningChain();

        String prompt = """
                Solve by writing executable code.
                Output:
                ```python
                # compute the answer, use print() for output
                ```
                Explanation: <why this works>

                Question: %s""".formatted(question);

        String response = llm.complete(LLMRequest.builder()
                .addMessage("user", prompt).maxTokens(2048).temperature(0.2).build()).content();

        chain.add(new ReasoningStep(1, "Generate code for: " + question,
                "Code generated", 0.85));

        String code = extractCode(response);
        if (code != null && !code.isBlank()) {
            String result = executeCode(code);
            chain.add(new ReasoningStep(2, "Execute: " + truncate(code, 80),
                    "Output: " + truncate(result, 200), 0.9));
        }

        String explanation = extractField(response, "Explanation[:：]\\s*", response);
        chain.add(new ReasoningStep(3, "Interpret results", explanation, 0.85));

        log.debug("ProgramOfThought: {} steps", chain.steps().size());
        return chain;
    }

    private String extractCode(String text) {
        var m = java.util.regex.Pattern.compile(
                "```(?:python|java|js)?\\s*\\n?(.*?)```", java.util.regex.Pattern.DOTALL).matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private String executeCode(String code) {
        try {
            ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");
            if (engine == null) engine = new ScriptEngineManager().getEngineByName("JavaScript");
            if (engine == null) engine = new ScriptEngineManager().getEngineByName("nashorn");
            if (engine != null) {
                Object result = engine.eval(code);
                return result != null ? result.toString() : "null";
            }
        } catch (Exception e) { log.debug("ScriptEngine failed: {}", e.getMessage()); }
        // Simple math evaluation fallback
        var m = java.util.regex.Pattern.compile("print\\s*\\(\\s*(.+?)\\s*\\)").matcher(code);
        if (m.find()) {
            try { return String.valueOf(Double.parseDouble(
                    m.group(1).replaceAll("\"", "").replaceAll("'", "").trim())); }
            catch (NumberFormatException e) {}
        }
        return "[Requires GraalJS for full execution]";
    }

    private String extractField(String text, String regex, String fallback) {
        var m = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? text.substring(m.end()).trim() : fallback;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
