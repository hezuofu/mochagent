package io.sketch.mochaagents.core;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Agent 统一抽象 — 融合 OOP 与函数式的核心接口.
 *
 * <p>参考需求文档 AIAgent&lt;I,O&gt; 接口设计，提供同步/异步/响应式执行及函数式组合能力.
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public interface Agent<I, O> {

    // ============ 核心执行方法 ============

    /**
     * 同步执行.
     */
    O execute(I input);

    /**
     * 异步执行.
     */
    CompletableFuture<O> executeAsync(I input);

    // ============ 元数据与监控 ============

    /**
     * 获取 Agent 元数据.
     */
    AgentMetadata metadata();

    /**
     * 添加监听器.
     */
    void addListener(AgentListener<I, O> listener);

    /**
     * 移除监听器.
     */
    void removeListener(AgentListener<I, O> listener);

    // ============ 函数式组合 (default 方法) ============

    /**
     * 前置处理 — 映射输入类型.
     */
    default <T> Agent<T, O> before(Function<T, I> mapper) {
        Agent<I, O> self = this;
        return new Agent<T, O>() {
            @Override public O execute(T input) { return self.execute(mapper.apply(input)); }
            @Override public CompletableFuture<O> executeAsync(T input) { return self.executeAsync(mapper.apply(input)); }
            @Override public AgentMetadata metadata() { return self.metadata(); }
            @Override public void addListener(AgentListener<T, O> listener) { /* composed, no-op */ }
            @Override public void removeListener(AgentListener<T, O> listener) { /* composed, no-op */ }
        };
    }

    /**
     * 后置处理 — 映射输出类型.
     */
    default <T> Agent<I, T> after(Function<O, T> mapper) {
        Agent<I, O> self = this;
        return new Agent<I, T>() {
            @Override public T execute(I input) { return mapper.apply(self.execute(input)); }
            @Override public CompletableFuture<T> executeAsync(I input) { return self.executeAsync(input).thenApply(mapper); }
            @Override public AgentMetadata metadata() { return self.metadata(); }
            @Override public void addListener(AgentListener<I, T> listener) { /* composed, no-op */ }
            @Override public void removeListener(AgentListener<I, T> listener) { /* composed, no-op */ }
        };
    }

    /**
     * 链式组合 — 当前 Agent 的输出作为下一个 Agent 的输入.
     */
    default <T> Agent<I, T> andThen(Agent<O, T> next) {
        Agent<I, O> self = this;
        return new Agent<I, T>() {
            @Override public T execute(I input) { return next.execute(self.execute(input)); }
            @Override public CompletableFuture<T> executeAsync(I input) { return self.executeAsync(input).thenCompose(next::executeAsync); }
            @Override public AgentMetadata metadata() { return self.metadata().and(next.metadata()); }
            @Override public void addListener(AgentListener<I, T> listener) { /* composed, no-op */ }
            @Override public void removeListener(AgentListener<I, T> listener) { /* composed, no-op */ }
        };
    }

    /**
     * 条件执行 — 条件满足时使用替代 Agent.
     */
    default Agent<I, O> when(Predicate<I> condition, Agent<I, O> alternative) {
        Agent<I, O> self = this;
        return new Agent<I, O>() {
            @Override public O execute(I input) { return condition.test(input) ? alternative.execute(input) : self.execute(input); }
            @Override public CompletableFuture<O> executeAsync(I input) { return condition.test(input) ? alternative.executeAsync(input) : self.executeAsync(input); }
            @Override public AgentMetadata metadata() { return self.metadata(); }
            @Override public void addListener(AgentListener<I, O> listener) { self.addListener(listener); alternative.addListener(listener); }
            @Override public void removeListener(AgentListener<I, O> listener) { self.removeListener(listener); alternative.removeListener(listener); }
        };
    }

    /**
     * 重试机制.
     */
    default Agent<I, O> withRetry(int maxAttempts) {
        Agent<I, O> self = this;
        return new Agent<I, O>() {
            @Override public O execute(I input) {
                RuntimeException last = null;
                for (int i = 0; i < maxAttempts; i++) {
                    try { return self.execute(input); } catch (RuntimeException e) { last = e; }
                }
                throw last != null ? last : new RuntimeException("retry exhausted");
            }
            @Override public CompletableFuture<O> executeAsync(I input) { return self.executeAsync(input); }
            @Override public AgentMetadata metadata() { return self.metadata(); }
            @Override public void addListener(AgentListener<I, O> listener) { self.addListener(listener); }
            @Override public void removeListener(AgentListener<I, O> listener) { self.removeListener(listener); }
        };
    }

    /**
     * 超时控制.
     */
    default Agent<I, O> withTimeout(long timeoutMillis) {
        Agent<I, O> self = this;
        return new Agent<I, O>() {
            @Override public O execute(I input) { return self.execute(input); }
            @Override public CompletableFuture<O> executeAsync(I input) {
                return self.executeAsync(input).orTimeout(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            @Override public AgentMetadata metadata() { return self.metadata(); }
            @Override public void addListener(AgentListener<I, O> listener) { self.addListener(listener); }
            @Override public void removeListener(AgentListener<I, O> listener) { self.removeListener(listener); }
        };
    }

}
