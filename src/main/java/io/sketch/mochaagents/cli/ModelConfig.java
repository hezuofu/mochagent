// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.cli;

import io.sketch.mochaagents.model.Model;
import io.sketch.mochaagents.model.ModelRequest;
import io.sketch.mochaagents.model.ModelResponse;
import io.sketch.mochaagents.model.provider.*;
import io.sketch.mochaagents.model.ModelRouter;
import io.sketch.mochaagents.model.CostOptimizer;
import io.sketch.mochaagents.model.FallbackStrategy;

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

    /** Build LLM(s) from config. Single model → direct Model. Multiple → ModelRouter. */
    public Model build() {
        if (models.isEmpty()) return new io.sketch.mochaagents.model.FallbackModel();

        if (models.size() == 1) {
            return buildOne(models.get(0));
        }

        // Multiple models: use ModelRouter for cost-optimized selection
        ModelRouter router = new ModelRouter(new io.sketch.mochaagents.model.CostOptimizer(),
                new io.sketch.mochaagents.model.FallbackStrategy());
        for (Entry e : models) {
            router.register(e.modelId(), buildOne(e));
        }
        return new RouterAdapter(router);
    }

    private Model buildOne(Entry e) {
        String provider = e.provider();
        String modelId = e.modelId();

        return switch (provider) {
            case "openai" -> OpenAIModel.builder().modelId(modelId)
                    .apiKey(env("OPENAI_API_KEY")).build();
            case "deepseek" -> DeepSeekModel.deepseekBuilder().modelId(modelId)
                    .apiKey(env("DEEPSEEK_API_KEY")).build();
            case "anthropic" -> AnthropicModel.builder().modelId(modelId)
                    .apiKey(env("ANTHROPIC_API_KEY")).build();
            case "ollama" -> OpenAICompatibleModel.forOllama(modelId);
            case "groq" -> OpenAICompatibleModel.compatibleBuilder().modelId(modelId)
                    .apiKey(env("GROQ_API_KEY")).baseUrl("https://api.groq.com/openai/v1").build();
            default -> OpenAICompatibleModel.compatibleBuilder().modelId(modelId)
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

    /** Thin adapter so Router can be used as direct Model for simple cases. */
    private record RouterAdapter(ModelRouter router) implements Model {
        @Override public ModelResponse complete(ModelRequest req) {
            return router.route(req).complete(req);
        }
        @Override public CompletableFuture<ModelResponse> completeAsync(ModelRequest req) {
            return router.route(req).completeAsync(req);
        }
        @Override public io.sketch.mochaagents.model.StreamingResponse stream(ModelRequest req) {
            return router.route(req).stream(req);
        }
        @Override public String modelName() { return "router[" + router.getProviders().size() + "]"; }
        @Override public int maxContextTokens() { return 128000; }
    }
}
