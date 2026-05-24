// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent;

import io.sketch.mochaagents.agent.event.AgentListener;

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

    /** Async execution — defaults to sync in a CompletableFuture. */
    default CompletableFuture<O> executeAsync(I input, AgentContext ctx) {
        return CompletableFuture.supplyAsync(() -> execute(input, ctx));
    }

    default CompletableFuture<O> executeAsync(I input) {
        return executeAsync(input, AgentContext.of(input != null ? input.toString() : ""));
    }

    // ============ Assembly ============

    /** Layer a Faculty onto this agent, returning an enhanced agent. */
    default Agent<I, O> with(Faculty<I, O> faculty) {
        return faculty.apply(this);
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
            // Listeners can't forward: input type T≠I
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
            // Listeners can't forward: output type O≠T
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
            // Listeners can't forward: type change I×O→I×T
            @Override public void addListener(AgentListener<I, T> l) {}
            @Override public void removeListener(AgentListener<I, T> l) {}
        };
    }

    /** 条件执行 — 条件满足时使用替代 Agent. */
    default Agent<I, O> when(Predicate<I> condition, Agent<I, O> alternative) {
        Agent<I, O> self = this;
        return new AgentWrapper<>(self) {
            @Override public O execute(I input, AgentContext ctx) {
                return condition.test(input) ? alternative.execute(input, ctx) : inner.execute(input, ctx);
            }
            @Override public void addListener(AgentListener<I, O> l) {
                self.addListener(l); alternative.addListener(l);
            }
            @Override public void removeListener(AgentListener<I, O> l) {
                self.removeListener(l); alternative.removeListener(l);
            }
        };
    }

    /** Retry on failure. */
    default Agent<I, O> withRetry(int maxAttempts) {
        return new AgentWrapper<>(this) {
            @Override public O execute(I input, AgentContext ctx) {
                RuntimeException last = null;
                for (int i = 0; i < maxAttempts; i++) {
                    try { return inner.execute(input, ctx); } catch (RuntimeException e) { last = e; }
                }
                throw last != null ? last : new RuntimeException("retry exhausted");
            }
        };
    }
}
