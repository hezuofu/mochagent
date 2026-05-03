package io.sketch.mochaagents.perception;

/**
 * 环境抽象 — Agent 所处环境的状态快照.
 * @author lanxia39@163.com
 */
public final class Environment {

    private final String id;
    private final java.util.Map<String, Object> state;
    private final long timestamp;

    public Environment(String id, java.util.Map<String, Object> state) {
        this.id = id;
        this.state = java.util.Collections.unmodifiableMap(new java.util.HashMap<>(state));
        this.timestamp = System.currentTimeMillis();
    }

    public String id() { return id; }
    public java.util.Map<String, Object> state() { return state; }
    public long timestamp() { return timestamp; }
}
