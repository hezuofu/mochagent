package io.sketch.mochaagents.agent.impl;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.event.AgentEvent;
import io.sketch.mochaagents.agent.event.AgentListener;
import io.sketch.mochaagents.agent.AgentMetadata;
import io.sketch.mochaagents.agent.AgentState;
import io.sketch.mochaagents.context.ContextChunk;
import io.sketch.mochaagents.context.Context;
import io.sketch.mochaagents.evaluation.EvaluationResult;
import io.sketch.mochaagents.evaluation.Evaluator;
import io.sketch.mochaagents.memory.Memory;
import io.sketch.mochaagents.memory.MemoryManager;
import io.sketch.mochaagents.reasoning.EffortLevel;
import io.sketch.mochaagents.reasoning.RecoveryStateMachine;
import io.sketch.mochaagents.reasoning.ThinkingConfig;
import io.sketch.mochaagents.safety.SafetyManager;
import io.sketch.mochaagents.tool.ToolRegistry;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal agent base — name, state, tools, safety, and memory.
 *
 * <p>Cognitive capabilities (perception, reasoning, planning, evaluation)
 * live in concrete loop strategies or are assembled via {@link io.sketch.mochaagents.agent.Faculty}.
 */
public abstract class BaseAgent<I, O> implements Agent<I, O> {

    protected final String name;
    protected final String description;
    protected final List<AgentListener<I, O>> listeners = new CopyOnWriteArrayList<>();
    protected volatile AgentState state = AgentState.IDLE;

    protected final ToolRegistry toolRegistry;
    protected final SafetyManager safetyManager;
    protected final MemoryManager memoryManager;

    protected final RecoveryStateMachine recovery;
    protected ThinkingConfig thinkingConfig;
    protected EffortLevel effortLevel;

    protected BaseAgent(Builder<I, O, ?> builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.toolRegistry = builder.toolRegistry;
        this.safetyManager = builder.safetyManager;
        this.memoryManager = builder.memoryManager;
        this.recovery = new RecoveryStateMachine();
        this.thinkingConfig = builder.thinkingConfig != null
                ? builder.thinkingConfig : ThinkingConfig.adaptive();
        this.effortLevel = builder.effortLevel != null
                ? builder.effortLevel : EffortLevel.HIGH;
    }

    protected abstract O doExecute(I input, AgentContext ctx);

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

    // ── Metadata / listeners ──

    @Override
    public AgentMetadata metadata() {
        return new AgentMetadata(name, description);
    }

    @Override
    public void addListener(AgentListener<I, O> l) { listeners.add(l); }
    @Override
    public void removeListener(AgentListener<I, O> l) { listeners.remove(l); }

    protected void fireStart(I input, AgentContext ctx) {
        AgentEvent<I> e = new AgentEvent<>(name, input, ctx);
        for (AgentListener<I, O> l : listeners) l.onStart(e);
    }

    protected void fireComplete(O output, AgentContext ctx) {
        AgentEvent<O> e = new AgentEvent<>(name, output, ctx);
        for (AgentListener<I, O> l : listeners) l.onComplete(e);
    }

    protected void fireError(Throwable err, AgentContext ctx) {
        AgentEvent<Throwable> e = new AgentEvent<>(name, err, ctx);
        for (AgentListener<I, O> l : listeners) l.onError(e);
    }

    // ── Shared utilities (used by subclasses) ──

    protected EvaluationResult evaluate(String task, String result,
                                        Evaluator evaluator, Context ctx) {
        if (evaluator == null) return null;
        return evaluator.evaluate(task, result, null);
    }

    protected void injectMemories(String task, Context ctx) {
        if (memoryManager == null) return;
        for (Memory m : memoryManager.search(task != null ? task : ""))
            ctx.addChunk(newChunk("memory", m.content()));
    }

    protected static ContextChunk newChunk(String role, String content) {
        int tokens = content != null ? Math.max(1, content.length() / 4) : 1;
        return new ContextChunk(UUID.randomUUID().toString(), role, content, tokens);
    }

    // ── Builder ──

    @SuppressWarnings("unchecked")
    public abstract static class Builder<I, O, T extends Builder<I, O, T>> {
        protected String name = "base-agent";
        protected String description = "";

        protected ToolRegistry toolRegistry;
        protected SafetyManager safetyManager;
        protected MemoryManager memoryManager;
        protected ThinkingConfig thinkingConfig;
        protected EffortLevel effortLevel;

        public T name(String n) { this.name = n; return (T) this; }
        public T description(String d) { this.description = d; return (T) this; }
        public T toolRegistry(ToolRegistry r) { this.toolRegistry = r; return (T) this; }
        public T safetyManager(SafetyManager s) { this.safetyManager = s; return (T) this; }
        public T memoryManager(MemoryManager m) { this.memoryManager = m; return (T) this; }
        public T thinkingConfig(ThinkingConfig c) { this.thinkingConfig = c; return (T) this; }
        public T effortLevel(EffortLevel e) { this.effortLevel = e; return (T) this; }

        public abstract BaseAgent<I, O> build();
    }
}
