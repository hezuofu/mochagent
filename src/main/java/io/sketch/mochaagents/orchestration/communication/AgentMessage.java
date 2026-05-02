package io.sketch.mochaagents.orchestration.communication;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 消息 — Agent 之间通信的消息载体.
 */
public class AgentMessage {

    private final String id;
    private final String senderId;
    private final String receiverId;
    private final MessageType type;
    private final String content;
    private final Instant timestamp;
    private final Map<String, Object> metadata;

    public AgentMessage(String senderId, String receiverId, MessageType type,
                        String content, Map<String, Object> metadata) {
        this.id = UUID.randomUUID().toString();
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.type = type;
        this.content = content;
        this.timestamp = Instant.now();
        this.metadata = Map.copyOf(metadata);
    }

    public String id() { return id; }
    public String senderId() { return senderId; }
    public String receiverId() { return receiverId; }
    public MessageType type() { return type; }
    public String content() { return content; }
    public Instant timestamp() { return timestamp; }
    public Map<String, Object> metadata() { return metadata; }

    public enum MessageType {
        TASK_ASSIGN, TASK_RESULT, QUERY, RESPONSE, BROADCAST, HEARTBEAT
    }

    public static AgentMessage task(String senderId, String receiverId, String task) {
        return new AgentMessage(senderId, receiverId, MessageType.TASK_ASSIGN, task, Map.of());
    }

    public static AgentMessage result(String senderId, String receiverId, Object result) {
        return new AgentMessage(senderId, receiverId, MessageType.TASK_RESULT,
                result != null ? result.toString() : "null", Map.of());
    }

    public static AgentMessage broadcast(String senderId, String content) {
        return new AgentMessage(senderId, "ALL", MessageType.BROADCAST, content, Map.of());
    }
}
