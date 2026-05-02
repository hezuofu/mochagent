package io.sketch.mochaagents.tool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 工具接口 — 核心工具抽象，定义工具元数据与调用契约.
 */
public interface Tool {
    String getName();
    String getDescription();
    Map<String, ToolInput> getInputs();
    String getOutputType();
    Object call(Map<String, Object> arguments);
    SecurityLevel getSecurityLevel();

    enum SecurityLevel { LOW, MEDIUM, HIGH, CRITICAL }
}
