package io.sketch.mochaagents.agent.impl;

import io.sketch.mochaagents.agent.Agent;
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
 * <p>子类覆写 {@link #doExecute(Object)} 实现核心逻辑，基类负责监听器分发和状态管理.
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
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
     * 子类实现核心执行逻辑.
     */
    protected abstract O doExecute(I input);

    @Override
    public O execute(I input) {
        state = AgentState.RUNNING;
        fireStart(input);
        try {
            O result = doExecute(input);
            state = AgentState.COMPLETED;
            fireComplete(result);
            return result;
        } catch (Exception e) {
            state = AgentState.FAILED;
            fireError(e);
            throw e;
        }
    }

    @Override
    public CompletableFuture<O> executeAsync(I input) {
        return CompletableFuture.supplyAsync(() -> execute(input));
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

    protected void fireStart(I input) {
        AgentEvent<I> event = new AgentEvent<>(name, input);
        for (AgentListener<I, O> l : listeners) {
            l.onStart(event);
        }
    }

    protected void fireComplete(O output) {
        AgentEvent<O> event = new AgentEvent<>(name, output);
        for (AgentListener<I, O> l : listeners) {
            l.onComplete(event);
        }
    }

    protected void fireError(Throwable error) {
        AgentEvent<Throwable> event = new AgentEvent<>(name, error);
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
