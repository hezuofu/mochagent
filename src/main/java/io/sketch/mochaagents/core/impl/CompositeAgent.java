package io.sketch.mochaagents.core.impl;

import io.sketch.mochaagents.core.Agent;
import io.sketch.mochaagents.core.AgentListener;
import io.sketch.mochaagents.core.AgentMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 组合 Agent — 将多个 Agent 按列表顺序执行，聚合所有输出.
 *
 * <p>类似 Composite 模式，多个子 Agent 的结果合并为一个列表输出.
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public class CompositeAgent<I, O> implements Agent<I, List<O>> {

    private final List<Agent<I, O>> agents;
    private final String name;

    public CompositeAgent(List<Agent<I, O>> agents) {
        this(agents, "composite");
    }

    public CompositeAgent(List<Agent<I, O>> agents, String name) {
        this.agents = new ArrayList<>(agents);
        this.name = name;
    }

    @Override
    public List<O> execute(I input) {
        List<O> results = new ArrayList<>();
        for (Agent<I, O> agent : agents) {
            results.add(agent.execute(input));
        }
        return results;
    }

    @Override
    public CompletableFuture<List<O>> executeAsync(I input) {
        List<CompletableFuture<O>> futures = new ArrayList<>();
        for (Agent<I, O> agent : agents) {
            futures.add(agent.executeAsync(input));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<O> results = new ArrayList<>();
                    for (CompletableFuture<O> f : futures) {
                        results.add(f.join());
                    }
                    return results;
                });
    }

    @Override
    public AgentMetadata metadata() {
        return AgentMetadata.builder().name(name).build();
    }

    @Override
    public void addListener(AgentListener<I, List<O>> listener) {
        // Composite 将监听器转发给子 Agent
        for (Agent<I, O> agent : agents) {
            agent.addListener(new AgentListener<I, O>() {
                @Override public void onComplete(io.sketch.mochaagents.core.AgentEvent<O> event) {
                    listener.onComplete(new io.sketch.mochaagents.core.AgentEvent<>(name, List.of(event.data())));
                }
            });
        }
    }

    @Override
    public void removeListener(AgentListener<I, List<O>> listener) {
        // no-op for simplicity
    }

    /**
     * 便捷工厂: 从多个 Agent 创建组合 Agent.
     */
    @SafeVarargs
    public static <I, O> CompositeAgent<I, O> of(Agent<I, O>... agents) {
        return new CompositeAgent<>(List.of(agents));
    }
}
