package io.sketch.mochaagents.llm.provider;

/**
 * DeepSeek 模型 Provider — OpenAI 兼容接口.
 *
 * <p>DeepSeek 提供两个模型:
 * <ul>
 *   <li><b>deepseek-chat</b> — DeepSeek-V3, 通用对话, 上下文 64K</li>
 *   <li><b>deepseek-reasoner</b> — DeepSeek-R1, 推理增强, 上下文 64K</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * DeepSeekLLM llm = DeepSeekLLM.deepseekBuilder()
 *         .modelId("deepseek-chat")
 *         .apiKey(System.getenv("DEEPSEEK_API_KEY"))
 *         .build();
 *
 * // 或使用快捷工厂
 * DeepSeekLLM llm = DeepSeekLLM.create();
 * }</pre>
 * @author lanxia39@163.com
 */
public class DeepSeekLLM extends OpenAICompatibleLLM {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";
    private static final String DEFAULT_MODEL = "deepseek-chat";

    protected DeepSeekLLM(DeepSeekBuilder builder) {
        super(new CompatibleBuilder()
                .modelId(builder.modelId)
                .maxContextTokens(builder.maxContextTokens)
                .connectTimeout(builder.connectTimeoutSeconds)
                .readTimeout(builder.readTimeoutSeconds)
                .requestsPerMinute(builder.requestsPerMinute)
                .apiKey(builder.apiKey)
                .baseUrl(builder.baseUrl));
    }

    /** 快捷工厂 — 使用环境变量 DEEPSEEK_API_KEY, 默认模型 deepseek-chat. */
    public static DeepSeekLLM create() {
        return deepseekBuilder().build();
    }

    public static DeepSeekBuilder deepseekBuilder() {
        return new DeepSeekBuilder();
    }

    public static final class DeepSeekBuilder {
        private String modelId = DEFAULT_MODEL;
        private int maxContextTokens = 65536;
        private int connectTimeoutSeconds = 30;
        private int readTimeoutSeconds = 300;
        private int requestsPerMinute = 0;
        private String apiKey = System.getenv("DEEPSEEK_API_KEY");
        private String baseUrl = DEFAULT_BASE_URL;

        public DeepSeekBuilder modelId(String id) { this.modelId = id; return this; }
        public DeepSeekBuilder maxContextTokens(int tokens) { this.maxContextTokens = tokens; return this; }
        public DeepSeekBuilder connectTimeout(int seconds) { this.connectTimeoutSeconds = seconds; return this; }
        public DeepSeekBuilder readTimeout(int seconds) { this.readTimeoutSeconds = seconds; return this; }
        public DeepSeekBuilder requestsPerMinute(int rpm) { this.requestsPerMinute = rpm; return this; }
        public DeepSeekBuilder apiKey(String key) { this.apiKey = key; return this; }
        public DeepSeekBuilder baseUrl(String url) { this.baseUrl = url; return this; }

        public DeepSeekLLM build() {
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = System.getenv("DEEPSEEK_API_KEY");
            }
            return new DeepSeekLLM(this);
        }
    }
}
