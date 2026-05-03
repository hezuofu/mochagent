package io.sketch.mochaagents.agent.impl;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.AgentEvent;
import io.sketch.mochaagents.agent.AgentListener;
import io.sketch.mochaagents.agent.AgentMetadata;
import io.sketch.mochaagents.agent.AgentState;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent 基础抽象实现 — Template Method 模式.
 *
 * <p>子类覆写 {@link #doExecute(Object, AgentContext)} 实现核心逻辑，
 * 基类负责监听器分发和状态管理.
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 * @author lanxia39@163.com
 */
public abstract class BaseAgent<I, O> implements Agent<I, O> {

    protected final String name;
    protected final String description;
    protected final List<AgentListener<I, O>> listeners = new CopyOnWriteArrayList<>();
    protected volatile AgentState state = AgentState.IDLE;

    protected BaseAgent(Builder<I, O, ?> builder) {
        this.name = builder.name;
        this.description = builder.description;
    }

    // ============ Template Method ============

    /**
     * 子类实现核心执行逻辑（主入口）.
     */
    protected abstract O doExecute(I input, AgentContext ctx);

    /**
     * 向后兼容 — 子类可覆写以支持旧的 execute(I) 调用.
     */
    protected O doExecute(I input) {
        return doExecute(input, AgentContext.of(input != null ? input.toString() : ""));
    }

    @Override
    public O execute(I input, AgentContext ctx) {
        state = AgentState.RUNNING;
        fireStart(input, ctx);
        try {
            O result = doExecute(input, ctx);
            state = AgentState.COMPLETED;
            fireComplete(result, ctx);
            return result;
        } catch (Exception e) {
            state = AgentState.FAILED;
            fireError(e, ctx);
            throw e;
        }
    }

    @Override
    public CompletableFuture<O> executeAsync(I input, AgentContext ctx) {
        return CompletableFuture.supplyAsync(() -> execute(input, ctx));
    }

    // ============ 元数据 ============

    @Override
    public AgentMetadata metadata() {
        return AgentMetadata.builder()
                .name(name)
                .description(description)
                .build();
    }

    // ============ 监听器 ============

    @Override
    public void addListener(AgentListener<I, O> listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(AgentListener<I, O> listener) {
        listeners.remove(listener);
    }

    protected void fireStart(I input, AgentContext ctx) {
        AgentEvent<I> event = new AgentEvent<>(name, input, ctx);
        for (AgentListener<I, O> l : listeners) {
            l.onStart(event);
        }
    }

    protected void fireComplete(O output, AgentContext ctx) {
        AgentEvent<O> event = new AgentEvent<>(name, output, ctx);
        for (AgentListener<I, O> l : listeners) {
            l.onComplete(event);
        }
    }

    protected void fireError(Throwable error, AgentContext ctx) {
        AgentEvent<Throwable> event = new AgentEvent<>(name, error, ctx);
        for (AgentListener<I, O> l : listeners) {
            l.onError(event);
        }
    }

    // ============ Builder ============

    @SuppressWarnings("unchecked")
    public abstract static class Builder<I, O, T extends Builder<I, O, T>> {
        protected String name = "base-agent";
        protected String description = "";

        public T name(String name) { this.name = name; return (T) this; }
        public T description(String description) { this.description = description; return (T) this; }

        public abstract BaseAgent<I, O> build();
    }
}
