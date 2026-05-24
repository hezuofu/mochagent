// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.model;

import java.util.Map;

/**
 * Model 响应 — 封装模型返回的文本、token 统计等信息.
 * @author lanxia39@163.com
 */
public class ModelResponse {

    private final String content;
    private final String model;
    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;
    private final long latencyMs;
    private final Map<String, Object> metadata;

    public ModelResponse(String content, String model, int promptTokens,
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

    public static ModelResponse of(String content) {
        return new ModelResponse(content, "unknown", 0, 0, 0, Map.of());
    }
}
