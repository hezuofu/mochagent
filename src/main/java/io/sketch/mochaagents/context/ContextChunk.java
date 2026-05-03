package io.sketch.mochaagents.context;

/**
 * 上下文块 — 上下文的最小存储单位.
 * @author lanxia39@163.com
 */
public final class ContextChunk {

    private final String id;
    private final String role;
    private final String content;
    private final int tokenCount;
    private final long timestamp;

    public ContextChunk(String id, String role, String content, int tokenCount) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.tokenCount = tokenCount;
        this.timestamp = System.currentTimeMillis();
    }

    public String id() { return id; }
    public String role() { return role; }
    public String content() { return content; }
    public int tokenCount() { return tokenCount; }
    public long timestamp() { return timestamp; }
}
