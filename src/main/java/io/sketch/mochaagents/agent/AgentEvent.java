package io.sketch.mochaagents.agent;

/**
 * 通用事件载体 — 携带事件源、时间戳与载荷数据.
 *
 * @param <T> 载荷类型
 * @author lanxia39@163.com
 */
public final class AgentEvent<T> {

    private final String source;
    private final T data;
    private final long timestamp;

    public AgentEvent(String source, T data) {
        this.source = source;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public String source() { return source; }
    public T data() { return data; }
    public long timestamp() { return timestamp; }
}
