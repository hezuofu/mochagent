// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent;

import io.sketch.mochaagents.agent.event.AgentListener;
import io.sketch.mochaagents.agent.internal.*;
import io.sketch.mochaagents.agent.loop.*;
import io.sketch.mochaagents.agent.loop.strategy.ReflexionLoop;
import io.sketch.mochaagents.agent.loop.strategy.ReWOOLoop;
import io.sketch.mochaagents.llm.*;
import io.sketch.mochaagents.orchestration.Orchestrator;
import io.sketch.mochaagents.prompt.PromptTemplate;
import io.sketch.mochaagents.reasoning.*;
import io.sketch.mochaagents.tool.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * MochaAgent — unified agent facade, composable via {@link Agent#andThen}.
 *
 * <pre>{@code
 * var agent = MochaAgent.builder("assistant", llm)
 *     .addTool(new WebSearchTool())
 *     .build();
 * String result = agent.run("What is the weather?");
 * }</pre>
 */
public final class MochaAgent implements Agent<String, String> {

    private final Agent<String, String> inner;

    private MochaAgent(Agent<String, String> inner) { this.inner = inner; }

    @Override public String execute(String input, AgentContext ctx) { return inner.execute(input, ctx); }
    @Override public CompletableFuture<String> executeAsync(String input, AgentContext ctx) { return inner.executeAsync(input, ctx); }
    @Override public AgentMetadata metadata() { return inner.metadata(); }
    @Override public void addListener(AgentListener<String, String> l) { inner.addListener(l); }
    @Override public void removeListener(AgentListener<String, String> l) { inner.removeListener(l); }

    public String run(String task) { return execute(task); }

    public ExecutionReport runAndReport(String task) {
        if (inner instanceof ReActAgent ra) return ra.runAndReport(task);
        long start = System.currentTimeMillis();
        return new ExecutionReport(run(task), 1, System.currentTimeMillis() - start,
                0, 0, 0, List.of(), "Completed");
    }

    public String runStreaming(AgentContext ctx, java.util.function.Consumer<String> onToken) {
        if (inner instanceof ReActAgent ra) return ra.runStreaming(ctx, onToken);
        return execute(ctx.userMessage(), ctx);
    }

    public Agent<String, String> inner() { return inner; }

    public MochaAgent withLoop(AgentLoop<String, String> loop) {
        if (inner instanceof ReActAgent ra) return new MochaAgent(ra.withAgentLoop(loop));
        return this;
    }

    // ── Builder ──

    public static Builder builder(String name, LLM llm) { return new Builder(name, llm); }
    public static Builder builder() { return new Builder("mocha-agent", new FallbackLLM()); }

    public static final class Builder {
        private String name;
        private LLM llm;
        private String description = "";
        private int maxSteps = 20;
        private String systemPrompt;
        private final List<Tool> tools = new ArrayList<>();
        private ToolRegistry toolRegistry;
        private ThinkingConfig thinkingConfig;
        private EffortLevel effortLevel;
        private AgentLoop<String, String> loop;
        private Orchestrator orchestrator;
        private final List<Faculty<String, String>> faculties = new ArrayList<>();

        Builder(String name, LLM llm) { this.name = name; this.llm = llm; }

        public Builder name(String n) { name = n; return this; }
        public Builder llm(LLM l) { llm = l; return this; }
        public Builder description(String d) { description = d; return this; }
        public Builder maxSteps(int n) { maxSteps = n; return this; }
        public Builder systemPrompt(String sp) { systemPrompt = sp; return this; }
        public Builder addTool(Tool t) { tools.add(t); return this; }
        public Builder tools(List<Tool> ts) { tools.addAll(ts); return this; }
        public Builder toolRegistry(ToolRegistry r) { toolRegistry = r; return this; }
        public Builder thinkingConfig(ThinkingConfig c) { thinkingConfig = c; return this; }
        public Builder effortLevel(EffortLevel e) { effortLevel = e; return this; }
        public Builder loop(AgentLoop<String, String> l) { loop = l; return this; }
        public Builder orchestrator(Orchestrator o) { orchestrator = o; return this; }

        /** Layer a Faculty onto this agent. */
        public Builder with(Faculty<String, String> f) { faculties.add(f); return this; }

        public Builder reflexionLoop() { loop = new ReflexionLoop<>(null, ReflectionEngine.noop()); return this; }
        public Builder rewooLoop(ReWOOLoop.Reasoner r, ReWOOLoop.ToolExecutor e, ReWOOLoop.Synthesizer s) {
            loop = new ReWOOLoop<>(r, e, s); return this;
        }

        public MochaAgent build() {
            ToolCallingAgent.Builder b = ToolCallingAgent.builder()
                    .name(name).llm(llm).description(description).maxSteps(maxSteps);
            if (toolRegistry != null) b.toolRegistry(toolRegistry);
            if (!tools.isEmpty()) b.tools(tools);
            if (thinkingConfig != null) b.thinkingConfig(thinkingConfig);
            if (effortLevel != null) b.effortLevel(effortLevel);
            if (loop != null) b.agentLoop(loop);
            if (orchestrator != null) b.orchestrator(orchestrator);
            if (systemPrompt != null) b.systemPromptTemplate(PromptTemplate.of(systemPrompt));
            Agent<String, String> agent = b.build();
            for (Faculty<String, String> f : faculties) agent = f.apply(agent);
            return new MochaAgent(agent);
        }
    }
}
