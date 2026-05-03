package io.sketch.mochaagents.reasoning.strategy;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import io.sketch.mochaagents.reasoning.ReasoningChain;
import io.sketch.mochaagents.reasoning.ReasoningStep;
import io.sketch.mochaagents.reasoning.ReasoningStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chain of Thought — 提示 LLM 输出结构化推理步骤并解析为 ReasoningStep 序列.
 * @author lanxia39@163.com
 */
public class ChainOfThought implements ReasoningStrategy {

    private static final Logger log = LoggerFactory.getLogger(ChainOfThought.class);
    private static final Pattern STEP_PATTERN = Pattern.compile(
            "Step\\s*(\\d+)\\s*[:：]\\s*(.+?)(?:\\n|$)", Pattern.DOTALL);

    private final LLM llm;

    public ChainOfThought(LLM llm) {
        this.llm = llm;
    }

    @Override
    public ReasoningChain reason(String question) {
        String prompt = """
                You are a reasoning engine. For the question, break down your analysis into
                numbered steps with confidence scores (0.0-1.0).

                Example:
                Question: What is 15%% of 200?

                Step 1: Convert 15%% to decimal: 15/100 = 0.15
                Confidence: 0.95

                Step 2: Multiply: 200 * 0.15 = 30
                Confidence: 0.95

                Step 3: The answer is 30
                Confidence: 0.95

                ---
                Now answer this question:
                %s""".formatted(question);

        LLMRequest request = LLMRequest.builder()
                .addMessage("user", prompt)
                .maxTokens(2048)
                .temperature(0.3)
                .build();

        LLMResponse response = llm.complete(request);
        return parseIntoChain(response.content(), question);
    }

    private ReasoningChain parseIntoChain(String text, String question) {
        ReasoningChain chain = new ReasoningChain();
        String[] sections = text.split("(?=Step\\s*\\d+)");
        int stepIdx = 0;

        for (String section : sections) {
            if (section.isBlank()) continue;
            Matcher m = STEP_PATTERN.matcher(section);
            if (m.find()) {
                stepIdx++;
                String thought = m.group(2).trim().replaceAll("Confidence:\\s*[\\d.]+", "").trim();
                double confidence = extractConfidence(section);
                String conclusion = stepIdx == sections.length ? thought : "→ next step";
                chain.add(new ReasoningStep(stepIdx, thought, conclusion, confidence));
            }
        }

        if (chain.steps().isEmpty()) {
            chain.add(new ReasoningStep(1, "Analyzed: " + question, text, 0.8));
        }

        log.debug("ChainOfThought produced {} steps, avg confidence={}",
                chain.steps().size(), chain.averageConfidence());
        return chain;
    }

    private double extractConfidence(String text) {
        Matcher m = Pattern.compile("Confidence\\s*[:：]\\s*([\\d.]+)").matcher(text);
        if (m.find()) {
            try { return Math.min(1.0, Math.max(0.0, Double.parseDouble(m.group(1)))); }
            catch (NumberFormatException e) { /* fall through */ }
        }
        return 0.8;
    }
}
