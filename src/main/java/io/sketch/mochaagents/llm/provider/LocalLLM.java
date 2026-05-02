package io.sketch.mochaagents.llm.provider;

/**
 * 本地 LLM 提供者 — Ollama / vLLM 等本地部署模型的便捷封装.
 *
 * <p>继承自 {@link OpenAICompatibleLLM}, 默认连接 localhost.
 *
 * <pre>{@code
 * // 默认: Ollama @ localhost:11434, model=llama3.2
 * LocalLLM llm = LocalLLM.builder().build();
 *
 * // vLLM @ localhost:8000
 * LocalLLM vllm = LocalLLM.builder()
 *         .modelId("meta-llama/Llama-3.1-70B-Instruct")
 *         .endpoint("http://localhost:8000/v1")
 *         .build();
 * }</pre>
 */
public class LocalLLM extends OpenAICompatibleLLM {

    protected LocalLLM(LocalBuilder builder) {
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
    public static LocalLLM create() {
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

        public LocalLLM build() {
            return new LocalLLM(this);
        }
    }
}
