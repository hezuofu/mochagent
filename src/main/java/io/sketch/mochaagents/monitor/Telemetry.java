package io.sketch.mochaagents.monitor;

/**
 * 遥测接口 — Agent 运行时的可观测性数据收集.
 * @author lanxia39@163.com
 */
public interface Telemetry {

    /** 记录事件 */
    void trackEvent(String eventName, java.util.Map<String, Object> properties);

    /** 记录指标 */
    void trackMetric(String metricName, double value);

    /** 记录异常 */
    void trackException(Throwable exception);

    /** 开始操作追踪 */
    String startOperation(String operationName);

    /** 结束操作追踪 */
    void endOperation(String operationId, String status);
}
