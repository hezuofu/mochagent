package io.sketch.mochaagents.llm;

import java.util.Map;

/**
 * LLM 响应 — 封装模型返回的文本、token 统计等信息.
 */
public class LLMResponse {

    private final String content;
    private final String model;
    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;
    private final long latencyMs;
    private final Map<String, Object> metadata;

    public LLMResponse(String content, String model, int promptTokens,
                       int completionTokens, long latencyMs, Map<String, Object> metadata) {
        this.content = content;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = promptTokens + completionTokens;
        this.latencyMs = latencyMs;
        this.metadata = Map.copyOf(metadata);
    }

    public String content() { return content; }
    public String model() { return model; }
    public int promptTokens() { return promptTokens; }
    public int completionTokens() { return completionTokens; }
    public int totalTokens() { return totalTokens; }
    public long latencyMs() { return latencyMs; }
    public Map<String, Object> metadata() { return metadata; }

    public static LLMResponse of(String content) {
        return new LLMResponse(content, "unknown", 0, 0, 0, Map.of());
    }
}
