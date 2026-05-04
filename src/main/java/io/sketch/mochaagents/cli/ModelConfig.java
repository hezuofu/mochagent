package io.sketch.mochaagents.cli;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import io.sketch.mochaagents.llm.provider.*;
import io.sketch.mochaagents.llm.router.LLMRouter;
import io.sketch.mochaagents.llm.router.CostOptimizer;
import io.sketch.mochaagents.llm.router.FallbackStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * CLI model configuration — parses --model/--provider/--temperature flags.
 * @author lanxia39@163.com
 */
public class ModelConfig {

    private final List<Entry> models = new ArrayList<>();
    private double temperature = 0.7;
    private int maxTokens = 4096;
    private boolean debug;

    record Entry(String modelId, String provider) {}

    public ModelConfig model(String modelId, String provider) {
        models.add(new Entry(modelId, provider != null ? provider : inferProvider(modelId)));
        return this;
    }

    public ModelConfig temperature(double t) { this.temperature = Math.max(0, Math.min(2, t)); return this; }
    public ModelConfig maxTokens(int n) { this.maxTokens = Math.max(1, n); return this; }
    public ModelConfig debug(boolean d) { this.debug = d; return this; }

    public double temperature() { return temperature; }
    public int maxTokens() { return maxTokens; }
    public boolean debug() { return debug; }
    public boolean hasModels() { return !models.isEmpty(); }

    /** Build LLM(s) from config. Single model → direct LLM. Multiple → LLMRouter. */
    public LLM build() {
        if (models.isEmpty()) return new io.sketch.mochaagents.llm.FallbackLLM();

        if (models.size() == 1) {
            return buildOne(models.get(0));
        }

        // Multiple models: use LLMRouter for cost-optimized selection
        LLMRouter router = new LLMRouter(new io.sketch.mochaagents.llm.router.CostOptimizer(),
                new io.sketch.mochaagents.llm.router.FallbackStrategy());
        for (Entry e : models) {
            router.register(e.modelId(), buildOne(e));
        }
        return new RouterAdapter(router);
    }

    private LLM buildOne(Entry e) {
        String provider = e.provider();
        String modelId = e.modelId();

        return switch (provider) {
            case "openai" -> OpenAILLM.builder().modelId(modelId)
                    .apiKey(env("OPENAI_API_KEY")).build();
            case "deepseek" -> DeepSeekLLM.deepseekBuilder().modelId(modelId)
                    .apiKey(env("DEEPSEEK_API_KEY")).build();
            case "anthropic" -> AnthropicLLM.builder().modelId(modelId)
                    .apiKey(env("ANTHROPIC_API_KEY")).build();
            case "ollama" -> OpenAICompatibleLLM.forOllama(modelId);
            case "groq" -> OpenAICompatibleLLM.compatibleBuilder().modelId(modelId)
                    .apiKey(env("GROQ_API_KEY")).baseUrl("https://api.groq.com/openai/v1").build();
            default -> OpenAICompatibleLLM.compatibleBuilder().modelId(modelId)
                    .apiKey(env(provider.toUpperCase() + "_API_KEY")).baseUrl(provider).build();
        };
    }

    private static String env(String name) { return System.getenv(name); }

    private static String inferProvider(String modelId) {
        String lower = modelId.toLowerCase();
        if (lower.contains("gpt")) return "openai";
        if (lower.contains("claude")) return "anthropic";
        if (lower.contains("deepseek")) return "deepseek";
        if (lower.contains("llama") || lower.contains("mistral") || lower.contains("mixtral"))
            return "ollama";
        return "openai"; // default
    }

    /** Thin adapter so Router can be used as direct LLM for simple cases. */
    private record RouterAdapter(LLMRouter router) implements LLM {
        @Override public LLMResponse complete(LLMRequest req) {
            return router.route(req).complete(req);
        }
        @Override public CompletableFuture<LLMResponse> completeAsync(LLMRequest req) {
            return router.route(req).completeAsync(req);
        }
        @Override public io.sketch.mochaagents.llm.StreamingResponse stream(LLMRequest req) {
            return router.route(req).stream(req);
        }
        @Override public String modelName() { return "router[" + router.getProviders().size() + "]"; }
        @Override public int maxContextTokens() { return 128000; }
    }
}
