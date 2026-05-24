// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent;

import io.sketch.mochaagents.context.Context;
import io.sketch.mochaagents.context.ContextChunk;
import io.sketch.mochaagents.context.ContextCompressor;
import io.sketch.mochaagents.context.ContextManager;
import io.sketch.mochaagents.context.ContextStrategy;

import java.time.Instant;
import java.util.*;

/**
 * Universal agent context — session metadata + token window management.
 *
 * <p>Implements {@link Context} to unify request-level and token-level
 * context management under a single abstraction. Use {@link #of(String)}
 * for simple cases (ephemeral token window), or {@link #of(String, int)}
 * with explicit token budget.
 */
public final class AgentContext implements Context {

    // ── Request metadata ──

    private final String sessionId;
    private final String userId;
    private final String userMessage;
    private final String conversationHistory;
    private final Map<String, Object> metadata;
    private final Instant timestamp;
    private final ContextManager window;

    private AgentContext(Builder builder) {
        this.sessionId = builder.sessionId;
        this.userId = builder.userId;
        this.userMessage = builder.userMessage;
        this.conversationHistory = builder.conversationHistory;
        this.metadata = Collections.unmodifiableMap(new HashMap<>(builder.metadata));
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.window = builder.window != null ? builder.window
                : new ContextManager(8192, (chunks, mt) -> chunks, null);
    }

    // ── Request metadata accessors ──

    public String sessionId() { return sessionId; }
    public String userId() { return userId; }
    public String userMessage() { return userMessage; }
    public String conversationHistory() { return conversationHistory; }
    public Map<String, Object> metadata() { return metadata; }
    public Instant timestamp() { return timestamp; }

    // ── Context interface (delegates to token window) ──

    @Override public void addChunk(ContextChunk chunk) { window.addChunk(chunk); }
    @Override public List<ContextChunk> getContext() { return window.getContext(); }
    @Override public void compress() { window.compress(); }
    @Override public int tokenCount() { return window.tokenCount(); }
    @Override public int maxTokens() { return window.maxTokens(); }

    /** Direct access to the underlying token window (advanced use). */
    public ContextManager window() { return window; }

    // ── Faculty enrichment ──

    public AgentContext withPerception(Object data) {
        return copy().metadata("perception", data).build();
    }

    public AgentContext withReasoning(Object data) {
        return copy().metadata("reasoning", data).build();
    }

    public AgentContext withPlan(Object data) {
        return copy().metadata("plan", data).build();
    }

    private Builder copy() {
        return new Builder()
                .sessionId(sessionId).userId(userId).userMessage(userMessage)
                .conversationHistory(conversationHistory).timestamp(timestamp);
    }

    // ── Factory ──

    public static Builder builder() { return new Builder(); }

    public static AgentContext of(String userMessage) {
        return builder().userMessage(userMessage).build();
    }

    public static AgentContext of(String userMessage, int maxTokens) {
        return builder().userMessage(userMessage).maxTokens(maxTokens).build();
    }

    // ── Builder ──

    public static final class Builder {
        private String sessionId;
        private String userId;
        private String userMessage;
        private String conversationHistory;
        private final Map<String, Object> metadata = new HashMap<>();
        private Instant timestamp;
        private ContextManager window;

        public Builder sessionId(String id) { this.sessionId = id; return this; }
        public Builder userId(String id) { this.userId = id; return this; }
        public Builder userMessage(String msg) { this.userMessage = msg; return this; }
        public Builder conversationHistory(String h) { this.conversationHistory = h; return this; }
        public Builder metadata(String key, Object value) { this.metadata.put(key, value); return this; }
        public Builder timestamp(Instant t) { this.timestamp = t; return this; }
        public Builder maxTokens(int tokens) {
            this.window = new ContextManager(tokens, (chunks, mt) -> chunks, null);
            return this;
        }
        public Builder window(ContextManager w) { this.window = w; return this; }

        public AgentContext build() { return new AgentContext(this); }
    }
}
