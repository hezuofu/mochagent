package io.sketch.mochaagents.context;

import java.util.List;

/**
 * 上下文压缩器接口 — 当上下文超过限制时进行压缩.
 */
public interface ContextCompressor {

    /**
     * 压缩上下文块列表.
     *
     * @param chunks    原始上下文块
     * @param maxTokens 目标最大 token 数
     * @return 压缩后的上下文块列表
     */
    List<ContextChunk> compress(List<ContextChunk> chunks, int maxTokens);
}
