// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.reasoning;

import io.sketch.mochaagents.model.Model;
import io.sketch.mochaagents.model.ModelRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

/**
 * Program of Thought — 让 Model 生成可执行代码来推理，执行代码并基于输出得出结论.
 * @author lanxia39@163.com
 */
public class ProgramOfThought implements ReasoningStrategy {

    private static final Logger log = LoggerFactory.getLogger(ProgramOfThought.class);
    private final Model model;

    public ProgramOfThought(Model model) { this.model = model; }

    @Override
    public ReasoningChain reason(String question) {
        ReasoningChain chain = new ReasoningChain();

        String prompt = """
                Solve this problem by writing JavaScript code.
                Output format:
                ```javascript
                // Compute the answer, use console.log() or return the result
                ```
                Then on a new line write: Explanation: <why this approach works>

                Question: %s""".formatted(question);

        String response = model.complete(ModelRequest.builder()
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
            if (engine == null) {
                engine = new ScriptEngineManager().getEngineByName("JavaScript");
            }
            if (engine == null) {
                engine = new ScriptEngineManager().getEngineByName("nashorn");
            }
            if (engine != null) {
                Object result = engine.eval(code);
                return result != null ? result.toString() : "null";
            }
        } catch (Exception e) {
            log.debug("ScriptEngine failed: {}", e.getMessage());
        }
        // Simple math evaluation fallback
        var m = java.util.regex.Pattern.compile("print\\s*\\(\\s*(.+?)\\s*\\)").matcher(code);
        if (m.find()) {
            try {
                return String.valueOf(Double.parseDouble(
                    m.group(1).replaceAll("\"", "").replaceAll("'", "").trim()));
            } catch (NumberFormatException e) {

            }
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
