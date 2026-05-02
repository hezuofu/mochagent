package io.sketch.mochaagents.llm;

import java.util.concurrent.CompletableFuture;

/**
 * LLM 接口 — 大语言模型的核心抽象，统一同步与异步调用.
 */
public interface LLM {

    /** 同步调用 */
    LLMResponse complete(LLMRequest request);

    /** 异步调用 */
    CompletableFuture<LLMResponse> completeAsync(LLMRequest request);

    /** 流式调用 */
    StreamingResponse stream(LLMRequest request);

    /** 模型名称 */
    String modelName();

    /** 最大上下文长度 */
    int maxContextTokens();
}
