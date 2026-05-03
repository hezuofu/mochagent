package io.sketch.mochaagents.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cost tracker — monitors LLM token usage and cost per model/session.
 * <p>Pricing per 1M tokens (input, output) for major models.
 * @author lanxia39@163.com
 */
public class CostTracker {

    private static final Logger log = LoggerFactory.getLogger(CostTracker.class);

    // Price per 1M tokens: [input, output]
    private static final Map<String, double[]> PRICING = Map.ofEntries(
            Map.entry("gpt-4o", new double[]{2.50, 10.00}),
            Map.entry("gpt-4o-mini", new double[]{0.15, 0.60}),
            Map.entry("gpt-4-turbo", new double[]{10.00, 30.00}),
            Map.entry("claude-sonnet-4-20250514", new double[]{3.00, 15.00}),
            Map.entry("claude-haiku-4-5-20251001", new double[]{0.80, 4.00}),
            Map.entry("claude-opus-4-20250514", new double[]{15.00, 75.00}),
            Map.entry("deepseek-chat", new double[]{0.27, 1.10}),
            Map.entry("deepseek-reasoner", new double[]{0.55, 2.19}),
            Map.entry("llama-3.3-70b", new double[]{0.59, 0.79}),
            Map.entry("gemini-2.0-flash", new double[]{0.10, 0.40}),
            Map.entry("qwen-plus", new double[]{0.80, 2.00})
    );

    private final AtomicLong totalInputTokens = new AtomicLong();
    private final AtomicLong totalOutputTokens = new AtomicLong();
    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong estimatedCostMicros = new AtomicLong(); // millionths of a dollar
    private final Map<String, long[]> perModel = new ConcurrentHashMap<>(); // [calls, inTokens, outTokens, costMicros]

    /** Record a completed LLM call. */
    public void record(String modelId, int inputTokens, int outputTokens) {
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);
        totalCalls.incrementAndGet();

        double[] prices = PRICING.getOrDefault(modelId, new double[]{1.0, 4.0});
        double cost = (inputTokens / 1_000_000.0) * prices[0]
                    + (outputTokens / 1_000_000.0) * prices[1];
        long costMicros = (long) (cost * 1_000_000);

        estimatedCostMicros.addAndGet(costMicros);
        perModel.compute(modelId, (k, v) -> {
            if (v == null) return new long[]{1, inputTokens, outputTokens, costMicros};
            v[0]++; v[1] += inputTokens; v[2] += outputTokens; v[3] += costMicros;
            return v;
        });

        log.debug("CostTracker: {} call #{}: {} in + {} out = ${}",
                modelId, totalCalls.get(), inputTokens, outputTokens, String.format("%.6f", cost));
    }

    /** Total input tokens across all calls. */
    public long totalInputTokens() { return totalInputTokens.get(); }
    /** Total output tokens across all calls. */
    public long totalOutputTokens() { return totalOutputTokens.get(); }
    /** Total number of LLM calls. */
    public long totalCalls() { return totalCalls.get(); }
    /** Estimated total cost in dollars. */
    public double estimatedTotalCost() { return estimatedCostMicros.get() / 1_000_000.0; }

    /** Per-model usage report. */
    public Map<String, ModelStats> perModelStats() {
        Map<String, ModelStats> result = new java.util.LinkedHashMap<>();
        perModel.forEach((model, data) -> result.put(model, new ModelStats(
                data[0], data[1], data[2], data[3] / 1_000_000.0)));
        return result;
    }

    /** Reset all counters. */
    public void reset() {
        totalInputTokens.set(0); totalOutputTokens.set(0);
        totalCalls.set(0); estimatedCostMicros.set(0);
        perModel.clear();
    }

    /** Generate a summary report. */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("LLM Usage: %d calls, %d in + %d out tokens, $%.4f total\n",
                totalCalls(), totalInputTokens(), totalOutputTokens(), estimatedTotalCost()));
        perModelStats().forEach((model, stats) ->
                sb.append(String.format("  %s: %d calls, $%.4f\n", model, stats.cost())));
        return sb.toString();
    }

    /** Per-model statistics record. */
    public record ModelStats(long calls, long inputTokens, long outputTokens, double cost) {}
}
