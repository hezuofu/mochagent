package io.sketch.mochaagents.llm.router;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import java.util.*;

/**
 * 成本优化器 — 根据预估 token 成本选择最优 LLM.
 * 包含主流模型的参考定价（美元/百万 token）.
 * @author lanxia39@163.com
 */
public class CostOptimizer {

    // Pricing per million tokens (input, output) for reference
    private static final Map<String, double[]> MODEL_PRICING = Map.of(
            "gpt-4o", new double[]{2.50, 10.00},
            "gpt-4o-mini", new double[]{0.15, 0.60},
            "gpt-4-turbo", new double[]{10.00, 30.00},
            "claude-sonnet-4-20250514", new double[]{3.00, 15.00},
            "claude-haiku-4-5-20251001", new double[]{0.80, 4.00},
            "deepseek-chat", new double[]{0.27, 1.10},
            "deepseek-reasoner", new double[]{0.55, 2.19},
            "qwen-plus", new double[]{0.80, 2.00},
            "llama3.2", new double[]{0.0, 0.0}
    );

    /**
     * 基于预估 token 成本和能力选择最优 LLM.
     */
    public LLM select(List<LLM> candidates, LLMRequest request) {
        if (candidates.isEmpty()) throw new IllegalArgumentException("No candidates");

        // Estimate token usage
        int estimatedTokens = estimateTokens(request);
        int maxTokens = request.maxTokens() > 0 ? request.maxTokens() : 4096;

        // Score each candidate: lower cost + higher context wins
        return candidates.stream()
                .min(Comparator.comparingDouble(c -> {
                    double cost = estimateCost(c.modelName(), estimatedTokens, maxTokens);
                    double contextPenalty = 1.0 / Math.max(1, c.maxContextTokens() / 1000.0);
                    return cost + contextPenalty;
                }))
                .orElse(candidates.get(0));
    }

    private double estimateCost(String modelName, int inputTokens, int outputTokens) {
        double[] prices = MODEL_PRICING.getOrDefault(modelName, new double[]{1.0, 4.0});
        return (inputTokens / 1_000_000.0) * prices[0] + (outputTokens / 1_000_000.0) * prices[1];
    }

    private int estimateTokens(LLMRequest request) {
        int tokens = 0;
        if (request.prompt() != null) tokens += request.prompt().length() / 4;
        if (request.messages() != null) {
            tokens += request.messages().stream()
                    .mapToInt(m -> m.getOrDefault("content", "").length() / 4).sum();
        }
        return Math.max(tokens, 100);
    }
}
