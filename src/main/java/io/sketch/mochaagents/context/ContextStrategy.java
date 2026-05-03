package io.sketch.mochaagents.context;

/**
 * 上下文策略 — 函数式接口，定义如何处理上下文.
 */
@FunctionalInterface
/** @author lanxia39@163.com */
public interface ContextStrategy {

    /**
     * 对上下文块应用策略.
     *
     * @param chunks 原始上下文块列表
     * @param maxTokens 最大 token 限制
     * @return 处理后的上下文块列表
     */
    java.util.List<ContextChunk> apply(java.util.List<ContextChunk> chunks, int maxTokens);
}
