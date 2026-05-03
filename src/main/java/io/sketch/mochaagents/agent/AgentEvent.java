package io.sketch.mochaagents.agent;

/**
 * 通用事件载体 — 携带事件源、时间戳、载荷数据与会话语境.
 *
 * @param <T> 载荷类型
 * @author lanxia39@163.com
 */
public final class AgentEvent<T> {

    private final String source;
    private final T data;
    private final long timestamp;
    private final AgentContext context;

    public AgentEvent(String source, T data) {
        this(source, data, null);
    }

    public AgentEvent(String source, T data, AgentContext context) {
        this.source = source;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
        this.context = context;
    }

    public String source() { return source; }
    public T data() { return data; }
    public long timestamp() { return timestamp; }
    public AgentContext context() { return context; }
}
