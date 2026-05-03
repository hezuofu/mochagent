package io.sketch.mochaagents.reasoning.verifier;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.reasoning.ReasoningChain;
import io.sketch.mochaagents.reasoning.ReasoningStep;

/**
 * 逻辑验证器 — 用 LLM 判断推理链的结论是否能从前提中逻辑推导出来.
 * @author lanxia39@163.com
 */
public class LogicVerifier implements ReasoningVerifier {

    private final LLM llm;
    private final double confidenceThreshold;

    public LogicVerifier(LLM llm) { this(llm, 0.5); }

    public LogicVerifier(LLM llm, double confidenceThreshold) {
        this.llm = llm;
        this.confidenceThreshold = confidenceThreshold;
    }

    @Override
    public boolean verify(ReasoningChain chain) {
        if (chain.steps().isEmpty()) return false;

        StringBuilder sb = new StringBuilder();
        for (ReasoningStep s : chain.steps()) {
            sb.append(s.index()).append(". ").append(s.thought())
              .append(" → ").append(s.conclusion()).append("\n");
        }

        String prompt = """
                Evaluate whether the final conclusion logically follows from the premises.
                Reply PASS if valid, FAIL if invalid, with one-line explanation.

                Chain:
                %s""".formatted(sb.toString());

        String response = llm.complete(LLMRequest.builder()
                .addMessage("user", prompt).maxTokens(256).temperature(0.1).build()).content();

        boolean passed = response.trim().toUpperCase().startsWith("PASS");
        return passed && chain.averageConfidence() >= confidenceThreshold;
    }

    @Override
    public String explain(ReasoningChain chain) {
        return "Logic: " + chain.steps().size() + " steps, confidence="
                + String.format("%.2f", chain.averageConfidence())
                + ", threshold=" + String.format("%.2f", confidenceThreshold)
                + " → " + (verify(chain) ? "PASS" : "FAIL");
    }
}
