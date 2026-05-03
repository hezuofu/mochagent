package io.sketch.mochaagents.agent;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Agent 统一抽象 — 核心接口，支持同步/异步执行及函数式组合.
 *
 * <p>{@link #execute(Object, AgentContext)} 为主入口，{@link #execute(Object)} 为向后兼容包装.
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 * @author lanxia39@163.com
 */
public interface Agent<I, O> {

    // ============ 核心执行方法 ============

    /**
     * 同步执行（主入口）.
     *
     * @param input 步骤级数据 — 本 Agent 处理什么
     * @param ctx   会话语境 — 谁、什么对话、元数据
     */
    O execute(I input, AgentContext ctx);

    /**
     * 向后兼容 — 使用默认 AgentContext 执行.
     */
    default O execute(I input) {
        return execute(input, AgentContext.of(input != null ? input.toString() : ""));
    }

    /**
     * 异步执行（主入口）.
     */
    CompletableFuture<O> executeAsync(I input, AgentContext ctx);

    /**
     * 向后兼容 — 使用默认 AgentContext 异步执行.
     */
    default CompletableFuture<O> executeAsync(I input) {
        return executeAsync(input, AgentContext.of(input != null ? input.toString() : ""));
    }

    // ============ 元数据与监控 ============

    AgentMetadata metadata();

    void addListener(AgentListener<I, O> listener);

    void removeListener(AgentListener<I, O> listener);

    // ============ 函数式组合 (default 方法, 透传 AgentContext) ============

    /** 前置处理 — 映射输入类型. */
    default <T> Agent<T, O> before(Function<T, I> mapper) {
        Agent<I, O> self = this;
        return new Agent<T, O>() {
            @Override public O execute(T input, AgentContext ctx) { return self.execute(mapper.apply(input), ctx); }
            @Override public CompletableFuture<O> executeAsync(T input, AgentContext ctx) { return self.executeAsync(mapper.apply(input), ctx); }
            @Override public AgentMetadata metadata() { return self.metadata(); }
            @Override public void addListener(AgentListener<T, O> l) {}
            @Override public void removeListener(AgentListener<T, O> l) {}
        };
    }

    /** 后置处理 — 映射输出类型. */
    default <T> Agent<I, T> after(Function<O, T> mapper) {
        Agent<I, O> self = this;
        return new Agent<I, T>() {
            @Override public T execute(I input, AgentContext ctx) { return mapper.apply(self.execute(input, ctx)); }
            @Override public CompletableFuture<T> executeAsync(I input, AgentContext ctx) { return self.executeAsync(input, ctx).thenApply(mapper); }
            @Override public AgentMetadata metadata() { return self.metadata(); }
            @Override public void addListener(AgentListener<I, T> l) {}
            @Override public void removeListener(AgentListener<I, T> l) {}
        };
    }

    /** 链式组合 — 当前 Agent 的输出作为下一个 Agent 的输入. */
    default <T> Agent<I, T> andThen(Agent<O, T> next) {
        Agent<I, O> self = this;
        return new Agent<I, T>() {
            @Override public T execute(I input, AgentContext ctx) { return next.execute(self.execute(input, ctx), ctx); }
            @Override public CompletableFuture<T> executeAsync(I input, AgentContext ctx) { return self.executeAsync(input, ctx).thenCompose(o -> next.executeAsync(o, ctx)); }
            @Override public AgentMetadata metadata() { return self.metadata().and(next.metadata()); }
            @Override public void addListener(AgentListener<I, T> l) {}
            @Override public void removeListener(AgentListener<I, T> l) {}
        };
    }

    /** 条件执行 — 条件满足时使用替代 Agent. */
    default Agent<I, O> when(Predicate<I> condition, Agent<I, O> alternative) {
        Agent<I, O> self = this;
        return new Agent<I, O>() {
            @Override public O execute(I input, AgentContext ctx) { return condition.test(input) ? alternative.execute(input, ctx) : self.execute(input, ctx); }
            @Override public CompletableFuture<O> executeAsync(I input, AgentContext ctx) { return condition.test(input) ? alternative.executeAsync(input, ctx) : self.executeAsync(input, ctx); }
            @Override public AgentMetadata metadata() { return self.metadata(); }
            @Override public void addListener(AgentListener<I, O> l) { self.addListener(l); alternative.addListener(l); }
            @Override public void removeListener(AgentListener<I, O> l) { self.removeListener(l); alternative.removeListener(l); }
        };
    }

    /** 重试机制. */
    default Agent<I, O> withRetry(int maxAttempts) {
        Agent<I, O> self = this;
        return new Agent<I, O>() {
            @Override public O execute(I input, AgentContext ctx) {
                RuntimeException last = null;
                for (int i = 0; i < maxAttempts; i++) {
                    try { return self.execute(input, ctx); } catch (RuntimeException e) { last = e; }
                }
                throw last != null ? last : new RuntimeException("retry exhausted");
            }
            @Override public CompletableFuture<O> executeAsync(I input, AgentContext ctx) { return self.executeAsync(input, ctx); }
            @Override public AgentMetadata metadata() { return self.metadata(); }
            @Override public void addListener(AgentListener<I, O> l) { self.addListener(l); }
            @Override public void removeListener(AgentListener<I, O> l) { self.removeListener(l); }
        };
    }

    /** 超时控制. */
    default Agent<I, O> withTimeout(long timeoutMillis) {
        Agent<I, O> self = this;
        return new Agent<I, O>() {
            @Override public O execute(I input, AgentContext ctx) { return self.execute(input, ctx); }
            @Override public CompletableFuture<O> executeAsync(I input, AgentContext ctx) {
                return self.executeAsync(input, ctx).orTimeout(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            @Override public AgentMetadata metadata() { return self.metadata(); }
            @Override public void addListener(AgentListener<I, O> l) { self.addListener(l); }
            @Override public void removeListener(AgentListener<I, O> l) { self.removeListener(l); }
        };
    }
}
