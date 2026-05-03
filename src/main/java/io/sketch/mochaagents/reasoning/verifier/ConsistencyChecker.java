package io.sketch.mochaagents.reasoning.verifier;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.reasoning.ReasoningChain;
import io.sketch.mochaagents.reasoning.ReasoningStep;

/**
 * 一致性检查 — 用 LLM 检测推理步骤间是否存在自相矛盾.
 * @author lanxia39@163.com
 */
public class ConsistencyChecker implements ReasoningVerifier {

    private final LLM llm;

    public ConsistencyChecker(LLM llm) { this.llm = llm; }

    @Override
    public boolean verify(ReasoningChain chain) {
        if (chain.steps().isEmpty()) return false;
        if (chain.steps().size() == 1) return true;

        StringBuilder sb = new StringBuilder();
        for (ReasoningStep s : chain.steps()) {
            sb.append("Step ").append(s.index()).append(": ").append(s.conclusion()).append("\n");
        }

        String prompt = """
                Check if these reasoning steps are logically consistent.
                Look for contradictions between steps.
                Reply with only PASS or FAIL followed by one-line reason.

                Steps:
                %s""".formatted(sb.toString());

        String response = llm.complete(LLMRequest.builder()
                .addMessage("user", prompt).maxTokens(256).temperature(0.1).build()).content();
        return response.trim().toUpperCase().startsWith("PASS");
    }

    @Override
    public String explain(ReasoningChain chain) {
        StringBuilder sb = new StringBuilder("Consistency check: ");
        sb.append(chain.steps().size()).append(" steps, ");
        sb.append("avg confidence=").append(String.format("%.2f", chain.averageConfidence())).append(", ");
        sb.append(verify(chain) ? "PASS" : "FAIL — possible contradiction");
        return sb.toString();
    }
}
