package io.sketch.mochaagents.llm;

import java.util.*;

/**
 * LLM 请求 — 封装 prompt、参数与配置.
 * @author lanxia39@163.com
 */
public class LLMRequest {

    private final String prompt;
    private final List<Map<String, String>> messages;
    private final double temperature;
    private final int maxTokens;
    private final double topP;
    private final List<String> stopSequences;
    private final Map<String, Object> extraParams;

    private LLMRequest(Builder builder) {
        this.prompt = builder.prompt;
        this.messages = List.copyOf(builder.messages);
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.topP = builder.topP;
        this.stopSequences = List.copyOf(builder.stopSequences);
        this.extraParams = Map.copyOf(builder.extraParams);
    }

    public String prompt() { return prompt; }
    public List<Map<String, String>> messages() { return messages; }
    public double temperature() { return temperature; }
    public int maxTokens() { return maxTokens; }
    public double topP() { return topP; }
    public List<String> stopSequences() { return stopSequences; }
    public Map<String, Object> extraParams() { return extraParams; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String prompt = "";
        private List<Map<String, String>> messages = new ArrayList<>();
        private double temperature = 0.7;
        private int maxTokens = 4096;
        private double topP = 1.0;
        private List<String> stopSequences = new ArrayList<>();
        private Map<String, Object> extraParams = new HashMap<>();

        public Builder prompt(String prompt) { this.prompt = prompt; return this; }
        public Builder messages(List<Map<String, String>> messages) { this.messages = messages; return this; }
        public Builder addMessage(String role, String content) {
            this.messages.add(Map.of("role", role, "content", content));
            return this;
        }
        public Builder temperature(double temperature) { this.temperature = temperature; return this; }
        public Builder maxTokens(int maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder topP(double topP) { this.topP = topP; return this; }
        public Builder stopSequences(List<String> stop) { this.stopSequences = stop; return this; }
        public Builder extraParams(Map<String, Object> params) { this.extraParams = params; return this; }

        public LLMRequest build() { return new LLMRequest(this); }
    }
}
