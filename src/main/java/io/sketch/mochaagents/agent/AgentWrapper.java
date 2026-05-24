package io.sketch.mochaagents.agent;

import io.sketch.mochaagents.agent.event.AgentListener;

import java.util.concurrent.CompletableFuture;

/**
 * Decorator base — delegates all Agent methods to an inner agent.
 * Subclasses override only the methods they need to intercept.
 */
public abstract class AgentWrapper<I, O> implements Agent<I, O> {

    protected final Agent<I, O> inner;

    protected AgentWrapper(Agent<I, O> inner) { this.inner = inner; }

    @Override public O execute(I input, AgentContext ctx) { return inner.execute(input, ctx); }

    @Override public CompletableFuture<O> executeAsync(I input, AgentContext ctx) {
        return CompletableFuture.supplyAsync(() -> execute(input, ctx));
    }

    @Override public AgentMetadata metadata() { return inner.metadata(); }

    @Override public void addListener(AgentListener<I, O> l) { inner.addListener(l); }

    @Override public void removeListener(AgentListener<I, O> l) { inner.removeListener(l); }
}
