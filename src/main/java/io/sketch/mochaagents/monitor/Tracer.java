package io.sketch.mochaagents.monitor;

import java.util.*;

/**
 * 链路追踪器 — 追踪 Agent 操作的完整调用链.
 */
public class Tracer {

    private final List<TraceSpan> spans = Collections.synchronizedList(new ArrayList<>());

    /** 创建追踪 Span */
    public Span startSpan(String name) {
        Span span = new Span(UUID.randomUUID().toString(), name, System.currentTimeMillis());
        spans.add(span);
        return span;
    }

    /** 获取所有追踪 */
    public List<TraceSpan> getTrace() {
        return List.copyOf(spans);
    }

    /** 构建调用树 */
    public String buildTree() {
        StringBuilder sb = new StringBuilder();
        for (TraceSpan span : spans) {
            sb.append("  ".repeat(Math.max(0, span.depth())))
              .append("└─ ").append(span.name())
              .append(" (").append(span.durationMs()).append("ms)\n");
        }
        return sb.toString();
    }

    /** 追踪 Span */
    public static class Span implements TraceSpan {
        private final String id;
        private final String name;
        private final long startTime;
        private long endTime;
        private String status;
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        Span(String id, String name, long startTime) {
            this.id = id; this.name = name; this.startTime = startTime;
        }

        public void end(String status) {
            this.endTime = System.currentTimeMillis();
            this.status = status;
        }

        public void setAttribute(String key, Object value) { attributes.put(key, value); }

        @Override public String id() { return id; }
        @Override public String name() { return name; }
        @Override public long startTime() { return startTime; }
        @Override public long endTime() { return endTime; }
        @Override public long durationMs() { return endTime - startTime; }
        @Override public String status() { return status; }
        @Override public int depth() { return 0; }
    }
}
