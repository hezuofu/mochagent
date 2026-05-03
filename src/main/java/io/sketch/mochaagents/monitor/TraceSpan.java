package io.sketch.mochaagents.monitor;

/**
 * TraceSpan 接口 — 追踪跨度的抽象表示.
 * @author lanxia39@163.com
 */
public interface TraceSpan {
    String id();
    String name();
    long startTime();
    long endTime();
    long durationMs();
    String status();
    int depth();
}
