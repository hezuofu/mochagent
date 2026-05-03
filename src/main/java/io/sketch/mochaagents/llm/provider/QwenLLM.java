package io.sketch.mochaagents.llm.provider;

/**
 * 通义千问 (Qwen) Provider — 通过阿里云 DashScope OpenAI 兼容接口接入.
 *
 * <p>可用模型:
 * <ul>
 *   <li><b>qwen-plus</b> — 综合性价比最优 (默认)</li>
 *   <li><b>qwen-max</b> — 最强能力</li>
 *   <li><b>qwen-turbo</b> — 最快速度</li>
 *   <li><b>qwen-long</b> — 长文档 (最高 10M tokens)</li>
 *   <li><b>qwen3-235b-a22b</b> — Qwen3 旗舰</li>
 * </ul>
 *
 * <p>前置条件: 在 <a href="https://dashscope.console.aliyun.com/apiKey">阿里云 DashScope</a>
 * 创建 API Key, 设置环境变量 {@code DASHSCOPE_API_KEY}.
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * QwenLLM llm = QwenLLM.qwenBuilder()
 *         .modelId("qwen-plus")
 *         .apiKey(System.getenv("DASHSCOPE_API_KEY"))
 *         .build();
 *
 * // 或使用快捷工厂
 * QwenLLM llm = QwenLLM.create();
 * }</pre>
 * @author lanxia39@163.com
 */
public class QwenLLM extends OpenAICompatibleLLM {

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_MODEL = "qwen-plus";

    protected QwenLLM(QwenBuilder builder) {
        super(new CompatibleBuilder()
                .modelId(builder.modelId)
                .maxContextTokens(builder.maxContextTokens)
                .connectTimeout(builder.connectTimeoutSeconds)
                .readTimeout(builder.readTimeoutSeconds)
                .requestsPerMinute(builder.requestsPerMinute)
                .apiKey(builder.apiKey)
                .baseUrl(builder.baseUrl));
    }

    /** 快捷工厂 — 使用环境变量 DASHSCOPE_API_KEY, 默认模型 qwen-plus. */
    public static QwenLLM create() {
        return qwenBuilder().build();
    }

    public static QwenBuilder qwenBuilder() {
        return new QwenBuilder();
    }

    public static final class QwenBuilder {
        private String modelId = DEFAULT_MODEL;
        private int maxContextTokens = 32768;
        private int connectTimeoutSeconds = 30;
        private int readTimeoutSeconds = 300;
        private int requestsPerMinute = 0;
        private String apiKey = System.getenv("DASHSCOPE_API_KEY");
        private String baseUrl = DEFAULT_BASE_URL;

        public QwenBuilder modelId(String id) { this.modelId = id; return this; }
        public QwenBuilder maxContextTokens(int tokens) { this.maxContextTokens = tokens; return this; }
        public QwenBuilder connectTimeout(int seconds) { this.connectTimeoutSeconds = seconds; return this; }
        public QwenBuilder readTimeout(int seconds) { this.readTimeoutSeconds = seconds; return this; }
        public QwenBuilder requestsPerMinute(int rpm) { this.requestsPerMinute = rpm; return this; }
        public QwenBuilder apiKey(String key) { this.apiKey = key; return this; }
        public QwenBuilder baseUrl(String url) { this.baseUrl = url; return this; }

        public QwenLLM build() {
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = System.getenv("DASHSCOPE_API_KEY");
            }
            return new QwenLLM(this);
        }
    }
}
