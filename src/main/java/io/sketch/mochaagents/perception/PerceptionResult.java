package io.sketch.mochaagents.perception;

/**
 * 感知结果 — 封装感知器输出，含耗时、token、置信度统计.
 * @author lanxia39@163.com
 */
public final class PerceptionResult<T> {

    private final T data;
    private final String type;
    private final double confidence;
    private final long processingTimeMs;
    private final int estimatedTokens;
    private final int inputTokens;

    private PerceptionResult(T data, String type, double confidence,
                              long processingTimeMs, int estimatedTokens, int inputTokens) {
        this.data = data;
        this.type = type;
        this.confidence = confidence;
        this.processingTimeMs = processingTimeMs;
        this.estimatedTokens = estimatedTokens;
        this.inputTokens = inputTokens;
    }

    public T data() { return data; }
    public String type() { return type; }
    public double confidence() { return confidence; }
    public long processingTimeMs() { return processingTimeMs; }

    /** 感知阶段产出的估计 token 数 (内容长度 / 4). */
    public int estimatedTokens() { return estimatedTokens; }

    /** 感知阶段消耗的输入 token 数. */
    public int inputTokens() { return inputTokens; }

    /** 感知处理是否产生了数据. */
    public boolean isEmpty() { return data == null; }

    // ============ 工厂方法 ============

    public static <T> PerceptionResult<T> of(T data, String type) {
        return new PerceptionResult<>(data, type, 1.0, 0, estimateTokens(data), 0);
    }

    public static <T> PerceptionResult<T> of(T data, String type, double confidence) {
        return new PerceptionResult<>(data, type, confidence, 0, estimateTokens(data), 0);
    }

    /** 完整构造 — 含耗时和 token 统计. */
    public static <T> PerceptionResult<T> full(T data, String type, double confidence,
                                                long processingTimeMs, int inputTokens) {
        return new PerceptionResult<>(data, type, confidence,
                processingTimeMs, estimateTokens(data), inputTokens);
    }

    @SuppressWarnings("unchecked")
    public static <T> PerceptionResult<T> empty() {
        return new PerceptionResult<>(null, "empty", 0.0, 0, 0, 0);
    }

    // ============ 报告 ============

    /** 生成单行摘要报告. */
    public String report() {
        return String.format("[Perception:%s] confidence=%.2f time=%dms tokens=%d(in=%d)",
                type, confidence, processingTimeMs, estimatedTokens, inputTokens);
    }

    /** 生成详细报告（含数据预览）. */
    public String detailedReport() {
        String preview = data != null ? data.toString() : "<empty>";
        if (preview.length() > 200) preview = preview.substring(0, 200) + "...";
        return String.format("""
                Perception Report
                  Type:        %s
                  Confidence:  %.2f
                  Time:        %dms
                  Tokens:      %d (input=%d, estimated=%d)
                  Data:        %s""",
                type, confidence, processingTimeMs,
                estimatedTokens + inputTokens, inputTokens, estimatedTokens, preview);
    }

    private static int estimateTokens(Object data) {
        if (data == null) return 0;
        String s = data.toString();
        return Math.max(1, s.length() / 4);
    }
}
