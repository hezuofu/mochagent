package io.sketch.mochaagents.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Tunable optimization parameters for LLM cost/latency/quality tradeoffs.
 * <p>Wire into agent builder: {@code .optimization(OptimizationConfig.balanced())}
 * @author lanxia39@163.com
 */
public class OptimizationConfig {

    // Retry
    private final int maxRetries;
    private final long retryBaseMs;
    private final double retryBackoff;

    // Timeouts
    private final long requestTimeoutMs;
    private final long streamTimeoutMs;

    // Caching
    private final int cacheMaxEntries;
    private final long cacheTtlSeconds;

    // Cost control
    private final double maxCostPerTask;
    private final boolean preferCheaperModel;

    // Model
    private final double temperature;
    private final int maxOutputTokens;
    private final boolean useStreaming;
    private final List<String> fallbackModels;

    private OptimizationConfig(Builder b) {
        this.maxRetries = b.maxRetries;
        this.retryBaseMs = b.retryBaseMs;
        this.retryBackoff = b.retryBackoff;
        this.requestTimeoutMs = b.requestTimeoutMs;
        this.streamTimeoutMs = b.streamTimeoutMs;
        this.cacheMaxEntries = b.cacheMaxEntries;
        this.cacheTtlSeconds = b.cacheTtlSeconds;
        this.maxCostPerTask = b.maxCostPerTask;
        this.preferCheaperModel = b.preferCheaperModel;
        this.temperature = b.temperature;
        this.maxOutputTokens = b.maxOutputTokens;
        this.useStreaming = b.useStreaming;
        this.fallbackModels = List.copyOf(b.fallbackModels);
    }

    // Getters
    public int maxRetries() { return maxRetries; }
    public long retryBaseMs() { return retryBaseMs; }
    public double retryBackoff() { return retryBackoff; }
    public long requestTimeoutMs() { return requestTimeoutMs; }
    public long streamTimeoutMs() { return streamTimeoutMs; }
    public int cacheMaxEntries() { return cacheMaxEntries; }
    public long cacheTtlSeconds() { return cacheTtlSeconds; }
    public double maxCostPerTask() { return maxCostPerTask; }
    public boolean preferCheaperModel() { return preferCheaperModel; }
    public double temperature() { return temperature; }
    public int maxOutputTokens() { return maxOutputTokens; }
    public boolean useStreaming() { return useStreaming; }
    public List<String> fallbackModels() { return fallbackModels; }

    // Presets
    public static OptimizationConfig cheapest() { return new Builder().preferCheaperModel(true).maxOutputTokens(1024).cacheMaxEntries(500).build(); }
    public static OptimizationConfig balanced() { return new Builder().build(); }
    public static OptimizationConfig maximum() { return new Builder().maxRetries(5).maxOutputTokens(8192).cacheMaxEntries(0).build(); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        int maxRetries = 3;
        long retryBaseMs = 1000;
        double retryBackoff = 2.0;
        long requestTimeoutMs = 120_000;
        long streamTimeoutMs = 300_000;
        int cacheMaxEntries = 100;
        long cacheTtlSeconds = 3600;
        double maxCostPerTask = 0;
        boolean preferCheaperModel;
        double temperature = 0.7;
        int maxOutputTokens = 4096;
        boolean useStreaming;
        List<String> fallbackModels = new ArrayList<>();

        public Builder maxRetries(int v) { this.maxRetries = v; return this; }
        public Builder retryBackoff(long baseMs, double multiplier) { this.retryBaseMs = baseMs; this.retryBackoff = multiplier; return this; }
        public Builder requestTimeout(long ms) { this.requestTimeoutMs = ms; return this; }
        public Builder streamTimeout(long ms) { this.streamTimeoutMs = ms; return this; }
        public Builder cacheMaxEntries(int v) { this.cacheMaxEntries = v; return this; }
        public Builder cacheTtl(long seconds) { this.cacheTtlSeconds = seconds; return this; }
        public Builder maxCostPerTask(double v) { this.maxCostPerTask = v; return this; }
        public Builder preferCheaperModel(boolean v) { this.preferCheaperModel = v; return this; }
        public Builder temperature(double v) { this.temperature = Math.max(0, Math.min(2, v)); return this; }
        public Builder maxOutputTokens(int v) { this.maxOutputTokens = Math.max(1, v); return this; }
        public Builder useStreaming(boolean v) { this.useStreaming = v; return this; }
        public Builder fallbackModel(String modelId) { this.fallbackModels.add(modelId); return this; }
        public Builder fallbackModels(List<String> v) { this.fallbackModels = new ArrayList<>(v); return this; }
        public OptimizationConfig build() { return new OptimizationConfig(this); }
    }
}
