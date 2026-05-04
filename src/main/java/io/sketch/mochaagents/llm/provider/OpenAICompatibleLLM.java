package io.sketch.mochaagents.llm.provider;

/**
 * 通用 OpenAI 兼容端点 — 覆盖 vLLM、Ollama、LiteLLM proxy、Groq、Cerebras 等.
 *
 * <p>与 {@link OpenAILLM} 共享相同的请求/响应格式, 但端点和认证完全可配置.
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // vLLM
 * OpenAICompatibleLLM vllm = OpenAICompatibleLLM.builder()
 *         .modelId("meta-llama/Llama-3.1-8B-Instruct")
 *         .baseUrl("http://localhost:8000/v1")
 *         .build();
 *
 * // Ollama
 * OpenAICompatibleLLM ollama = OpenAICompatibleLLM.builder()
 *         .modelId("llama3.2")
 *         .baseUrl("http://localhost:11434/v1")
 *         .build();
 *
 * // Groq
 * OpenAICompatibleLLM groq = OpenAICompatibleLLM.builder()
 *         .modelId("llama-3.1-70b-versatile")
 *         .baseUrl("https://api.groq.com/openai/v1")
 *         .apiKey(System.getenv("GROQ_API_KEY"))
 *         .build();
 *
 * // LiteLLM Proxy (routes to 100+ providers)
 * OpenAICompatibleLLM litellm = OpenAICompatibleLLM.builder()
 *         .modelId("gpt-4o")
 *         .baseUrl("http://localhost:4000/v1")
 *         .apiKey("sk-litellm-key")
 *         .build();
 * }</pre>
 * @author lanxia39@163.com
 */
public class OpenAICompatibleLLM extends OpenAILLM {

    protected OpenAICompatibleLLM(CompatibleBuilder builder) {
        super(new OpenAIBuilder()
                .modelId(builder.modelId)
                .maxContextTokens(builder.maxContextTokens)
                .connectTimeout(builder.connectTimeoutSeconds)
                .requestsPerMinute(builder.requestsPerMinute)
                .apiKey(builder.apiKey)
                .baseUrl(builder.baseUrl));
    }

    public static CompatibleBuilder compatibleBuilder() {
        return new CompatibleBuilder();
    }

    /**
     * 快捷工厂 — 连接本地 Ollama.
     */
    public static OpenAICompatibleLLM forOllama(String model) {
        return compatibleBuilder()
                .modelId(model)
                .baseUrl("http://localhost:11434/v1")
                .build();
    }

    /**
     * 快捷工厂 — 连接本地 vLLM.
     */
    public static OpenAICompatibleLLM forVLLM(String model, int port) {
        return compatibleBuilder()
                .modelId(model)
                .baseUrl("http://localhost:" + port + "/v1")
                .build();
    }

    public static final class CompatibleBuilder {
        private String modelId;
        private int maxContextTokens = 8192;
        private int connectTimeoutSeconds = 30;
        private int readTimeoutSeconds = 120;
        private int requestsPerMinute = 0;
        private String apiKey = "";
        private String baseUrl = "http://localhost:8000/v1";

        public CompatibleBuilder modelId(String id) { this.modelId = id; return this; }
        public CompatibleBuilder maxContextTokens(int tokens) { this.maxContextTokens = tokens; return this; }
        public CompatibleBuilder connectTimeout(int seconds) { this.connectTimeoutSeconds = seconds; return this; }
        public CompatibleBuilder readTimeout(int seconds) { this.readTimeoutSeconds = seconds; return this; }
        public CompatibleBuilder requestsPerMinute(int rpm) { this.requestsPerMinute = rpm; return this; }
        public CompatibleBuilder apiKey(String key) { this.apiKey = key; return this; }
        public CompatibleBuilder baseUrl(String url) { this.baseUrl = url; return this; }

        public OpenAICompatibleLLM build() {
            if (modelId == null) modelId = "default";
            return new OpenAICompatibleLLM(this);
        }
    }
}
