package io.sketch.mochaagents.perception;

/**
 * 观察结果 — 对环境的感知快照.
 * @author lanxia39@163.com
 */
public final class Observation {

    private final String source;
    private final String content;
    private final String type;
    private final long timestamp;

    public Observation(String source, String content, String type) {
        this.source = source;
        this.content = content;
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }

    public String source() { return source; }
    public String content() { return content; }
    public String type() { return type; }
    public long timestamp() { return timestamp; }
}
