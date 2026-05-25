// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.model;

import io.sketch.mochaagents.reasoning.ThinkingConfig;
import io.sketch.mochaagents.reasoning.EffortLevel;
import java.util.*;

/**
 * Model 请求 — 封装 prompt、参数与配置.
 * ThinkingConfig and EffortLevel flow through to providers for API parameter mapping.
 * @author lanxia39@163.com
 */
public class ModelRequest {

    private final String prompt;
    private final List<Map<String, String>> messages;
    private final List<io.sketch.mochaagents.agent.message.Message> typedMessages;
    private final double temperature;
    private final int maxTokens;
    private final double topP;
    private final List<String> stopSequences;
    private final double presencePenalty;
    private final double frequencyPenalty;
    private final Map<String, Object> extraParams;
    private final ThinkingConfig thinkingConfig;
    private final EffortLevel effort;
    private final List<io.sketch.mochaagents.tool.Tool> tools;

    private ModelRequest(Builder builder) {
        this.prompt = builder.prompt;
        this.messages = List.copyOf(builder.messages);
        this.typedMessages = builder.typedMessages != null ? List.copyOf(builder.typedMessages) : List.of();
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.topP = builder.topP;
        this.stopSequences = List.copyOf(builder.stopSequences);
        this.presencePenalty = builder.presencePenalty;
        this.frequencyPenalty = builder.frequencyPenalty;
        this.extraParams = Map.copyOf(builder.extraParams);
        this.thinkingConfig = builder.thinkingConfig;
        this.effort = builder.effort;
        this.tools = builder.tools;
    }

    public String prompt() { return prompt; }
    public List<Map<String, String>> messages() { return messages; }

    /** Typed messages (new API). Providers should check this first, fall back to messages(). */
    public List<io.sketch.mochaagents.agent.message.Message> typedMessages() { return typedMessages; }
    public double temperature() { return temperature; }
    public int maxTokens() { return maxTokens; }
    public double topP() { return topP; }
    public List<String> stopSequences() { return stopSequences; }
    public double presencePenalty() { return presencePenalty; }
    public double frequencyPenalty() { return frequencyPenalty; }
    public Map<String, Object> extraParams() { return extraParams; }
    public ThinkingConfig thinkingConfig() { return thinkingConfig; }
    public EffortLevel effort() { return effort; }
    public List<io.sketch.mochaagents.tool.Tool> tools() { return tools; }
    public boolean hasTools() { return tools != null && !tools.isEmpty(); }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String prompt = "";
        private List<Map<String, String>> messages = new ArrayList<>();
        private List<io.sketch.mochaagents.agent.message.Message> typedMessages;
        private double temperature = 0.7;
        private int maxTokens = 4096;
        private double topP = 1.0;
        private List<String> stopSequences = new ArrayList<>();
        private double presencePenalty;
        private double frequencyPenalty;
        private Map<String, Object> extraParams = new HashMap<>();
        private ThinkingConfig thinkingConfig;
        private EffortLevel effort;
        private List<io.sketch.mochaagents.tool.Tool> tools;

        public Builder prompt(String prompt) { this.prompt = prompt; return this; }
        public Builder messages(List<Map<String, String>> messages) { this.messages = messages; return this; }
        public Builder addMessage(String role, String content) {
            this.messages.add(Map.of("role", role, "content", content));
            return this;
        }

        public Builder typedMessages(List<io.sketch.mochaagents.agent.message.Message> msgs) {
            this.typedMessages = msgs; return this;
        }
        public Builder temperature(double temperature) { this.temperature = temperature; return this; }
        public Builder maxTokens(int maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder topP(double topP) { this.topP = topP; return this; }
        public Builder stopSequences(List<String> stop) { this.stopSequences = stop; return this; }
        public Builder presencePenalty(double v) { this.presencePenalty = Math.max(-2, Math.min(2, v)); return this; }
        public Builder frequencyPenalty(double v) { this.frequencyPenalty = Math.max(-2, Math.min(2, v)); return this; }
        public Builder extraParams(Map<String, Object> params) { this.extraParams = params; return this; }
        public Builder thinkingConfig(ThinkingConfig config) { this.thinkingConfig = config; return this; }
        public Builder effort(EffortLevel effort) { this.effort = effort; return this; }
        public Builder tools(List<io.sketch.mochaagents.tool.Tool> tools) { this.tools = tools; return this; }

        public ModelRequest build() { return new ModelRequest(this); }
    }
}
