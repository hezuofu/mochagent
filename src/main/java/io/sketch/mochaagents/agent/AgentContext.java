package io.sketch.mochaagents.agent;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Agent 执行上下文 — 封装单次调用的会话、用户、消息及元数据.
 *
 * <p>参考需求文档参考代码 AgentContext 类.
 * @author lanxia39@163.com
 */
public final class AgentContext {

    private final String sessionId;
    private final String userId;
    private final String userMessage;
    private final String conversationHistory;
    private final Map<String, Object> metadata;
    private final Instant timestamp;

    private AgentContext(Builder builder) {
        this.sessionId = builder.sessionId;
        this.userId = builder.userId;
        this.userMessage = builder.userMessage;
        this.conversationHistory = builder.conversationHistory;
        this.metadata = Collections.unmodifiableMap(new HashMap<>(builder.metadata));
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
    }

    public String sessionId() { return sessionId; }
    public String userId() { return userId; }
    public String userMessage() { return userMessage; }
    public String conversationHistory() { return conversationHistory; }
    public Map<String, Object> metadata() { return metadata; }
    public Instant timestamp() { return timestamp; }

    /**
     * 返回新的 Builder 实例.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 从纯文本消息创建最小上下文（向后兼容 run(String)）.
     */
    public static AgentContext of(String userMessage) {
        return builder().userMessage(userMessage).build();
    }

    public static final class Builder {
        private String sessionId;
        private String userId;
        private String userMessage;
        private String conversationHistory;
        private final Map<String, Object> metadata = new HashMap<>();
        private Instant timestamp;

        public Builder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder userMessage(String userMessage) { this.userMessage = userMessage; return this; }
        public Builder conversationHistory(String conversationHistory) { this.conversationHistory = conversationHistory; return this; }
        public Builder metadata(String key, Object value) { this.metadata.put(key, value); return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }

        public AgentContext build() {
            return new AgentContext(this);
        }
    }
}
