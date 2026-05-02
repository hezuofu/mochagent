package io.sketch.mochaagents.perception;

/**
 * 感知结果 — 封装感知器输出.
 */
public final class PerceptionResult<T> {

    private final T data;
    private final String type;
    private final double confidence;
    private final long processingTimeMs;

    private PerceptionResult(T data, String type, double confidence, long processingTimeMs) {
        this.data = data;
        this.type = type;
        this.confidence = confidence;
        this.processingTimeMs = processingTimeMs;
    }

    public T data() { return data; }
    public String type() { return type; }
    public double confidence() { return confidence; }
    public long processingTimeMs() { return processingTimeMs; }
    public boolean isEmpty() { return data == null; }

    public static <T> PerceptionResult<T> of(T data, String type) {
        return new PerceptionResult<>(data, type, 1.0, 0);
    }

    public static <T> PerceptionResult<T> of(T data, String type, double confidence) {
        return new PerceptionResult<>(data, type, confidence, 0);
    }

    @SuppressWarnings("unchecked")
    public static <T> PerceptionResult<T> empty() {
        return new PerceptionResult<>(null, "empty", 0.0, 0);
    }
}
