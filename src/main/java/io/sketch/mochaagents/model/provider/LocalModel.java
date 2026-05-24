// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.model.provider;

/**
 * 本地 Model 提供者 — Ollama / vModel 等本地部署模型的便捷封装.
 *
 * <p>继承自 {@link OpenAICompatibleModel}, 默认连接 localhost.
 *
 * <pre>{@code
 * // 默认: Ollama @ localhost:11434, model=llama3.2
 * LocalModel model = LocalModel.builder().build();
 *
 * // vModel @ localhost:8000
 * LocalModel vllm = LocalModel.builder()
 *         .modelId("meta-llama/Llama-3.1-70B-Instruct")
 *         .endpoint("http://localhost:8000/v1")
 *         .build();
 * }</pre>
 * @author lanxia39@163.com
 */
public class LocalModel extends OpenAICompatibleModel {

    protected LocalModel(LocalBuilder builder) {
        super(new CompatibleBuilder()
                .modelId(builder.modelId)
                .maxContextTokens(builder.maxContextTokens)
                .connectTimeout(builder.connectTimeoutSeconds)
                .readTimeout(builder.readTimeoutSeconds)
                .baseUrl(builder.endpoint));
    }

    public static LocalBuilder localBuilder() {
        return new LocalBuilder();
    }

    /**
     * 快捷工厂 — 使用默认配置 (Ollama llama3.2 @ localhost:11434).
     */
    public static LocalModel create() {
        return localBuilder().build();
    }

    public static final class LocalBuilder {
        private String modelId = "llama3.2";
        private int maxContextTokens = 8192;
        private int connectTimeoutSeconds = 30;
        private int readTimeoutSeconds = 120;
        private String endpoint = "http://localhost:11434/v1";

        public LocalBuilder modelId(String id) { this.modelId = id; return this; }
        public LocalBuilder maxContextTokens(int tokens) { this.maxContextTokens = tokens; return this; }
        public LocalBuilder connectTimeout(int seconds) { this.connectTimeoutSeconds = seconds; return this; }
        public LocalBuilder readTimeout(int seconds) { this.readTimeoutSeconds = seconds; return this; }
        public LocalBuilder endpoint(String url) { this.endpoint = url; return this; }

        public LocalModel build() {
            return new LocalModel(this);
        }
    }
}
