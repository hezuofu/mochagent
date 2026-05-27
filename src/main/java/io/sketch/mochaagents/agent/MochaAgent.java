// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent;

import io.sketch.mochaagents.agent.event.AgentListener;
import io.sketch.mochaagents.agent.loop.*;
import io.sketch.mochaagents.agent.loop.strategy.ReflexionLoop;
import io.sketch.mochaagents.agent.loop.strategy.ReWOOLoop;
import io.sketch.mochaagents.model.*;
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
 * var agent = MochaAgent.builder("assistant", model)
 *     .addTool(new WebSearchTool())
 *     .build();
 * String result = agent.run("What is the weather?");
 * }</pre>
  * @author lanxia39@163.com
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

    // ── Delegation — no need to unwrap inner ──

    private ReActAgent ra() { return (ReActAgent) inner; }

    public io.sketch.mochaagents.memory.MemoryManager memory() { return ra().memory(); }
    public io.sketch.mochaagents.event.EventBus events() { return ra().events(); }
    public void autoCompact() { ra().autoCompact(); }
    public void invalidateMessageCaches() { ra().invalidateMessageCaches(); }
    public io.sketch.mochaagents.tool.Hooks hooks() { return ra().hooks(); }

    // ── Builder ──

    public static Builder builder(String name, Model model) { return new Builder(name, model); }
    public static Builder builder() { return new Builder("mocha-agent", new FallbackModel()); }

    public static final class Builder {
        private String name;
        private Model model;
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
        private io.sketch.mochaagents.memory.MemoryPlugin memoryPlugin;
        private io.sketch.mochaagents.event.EventBus eventBus;
        private io.sketch.mochaagents.interaction.PermissionRules permissionRules;
        private io.sketch.mochaagents.interaction.DecisionPipeline decisionPipeline;
        private io.sketch.mochaagents.interaction.ApprovalBroker approvalBroker;
        private boolean globalMemory;
        private boolean antiForgetting;

        Builder(String name, Model model) { this.name = name; this.model = model; }

        public Builder name(String n) { name = n; return this; }
        public Builder model(Model l) { model = l; return this; }
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
        public Builder with(Faculty<String, String> f) { faculties.add(f); return this; }
        public Builder memoryPlugin(io.sketch.mochaagents.memory.MemoryPlugin p) { memoryPlugin = p; return this; }
        public Builder eventBus(io.sketch.mochaagents.event.EventBus bus) { eventBus = bus; return this; }
        public Builder globalMemory(boolean v) { globalMemory = v; return this; }
        public Builder antiForgetting(boolean v) { antiForgetting = v; return this; }
        public Builder permissionRules(io.sketch.mochaagents.interaction.PermissionRules r) { permissionRules = r; return this; }
        public Builder decisionPipeline(io.sketch.mochaagents.interaction.DecisionPipeline p) { decisionPipeline = p; return this; }
        public Builder approvalBroker(io.sketch.mochaagents.interaction.ApprovalBroker b) { approvalBroker = b; return this; }
        public Builder observability(io.sketch.mochaagents.observability.Observability o) { /* eventBus = null; observability used externally */ return this; }

        // Faculty shortcuts
        public Builder withPerception(io.sketch.mochaagents.perception.Perceptor<String, String> p) {
            return with(Faculty.Perception.of(p));
        }
        public Builder withReasoning(io.sketch.mochaagents.reasoning.Reasoner r) {
            return with(Faculty.Reasoning.of(r));
        }
        public Builder withPlanning(io.sketch.mochaagents.plan.Planner<String> p) {
            return with(Faculty.Planning.of(p));
        }
        public Builder withEvaluation(io.sketch.mochaagents.evaluation.Evaluator e) {
            return with(Faculty.Evaluation.of(e));
        }

        public Builder reflexionLoop() { loop = new ReflexionLoop<>(null, Reflector.noop()); return this; }
        public Builder rewooLoop(ReWOOLoop.Reasoner r, ReWOOLoop.ToolExecutor e, ReWOOLoop.Synthesizer s) {
            loop = new ReWOOLoop<>(r, e, s); return this;
        }

        public MochaAgent build() {
            ToolCallingAgent.Builder b = ToolCallingAgent.builder()
                    .name(name).model(model).description(description).maxSteps(maxSteps)
                    .antiForgetting(antiForgetting).globalMemory(globalMemory);
            if (toolRegistry != null) b.toolRegistry(toolRegistry);
            if (!tools.isEmpty()) b.tools(tools);
            if (thinkingConfig != null) b.thinkingConfig(thinkingConfig);
            if (effortLevel != null) b.effortLevel(effortLevel);
            if (loop != null) b.agentLoop(loop);
            if (orchestrator != null) b.orchestrator(orchestrator);
            if (systemPrompt != null) b.systemPromptTemplate(PromptTemplate.of(systemPrompt));
            if (permissionRules != null) b.permissionRules(permissionRules);
            Agent<String, String> agent = b.build();
            for (Faculty<String, String> f : faculties) agent = f.apply(agent);
            // Wire memory plugin + global memory + interaction (ToolExecutor side)
            if (agent instanceof ReActAgent ra) {
                if (memoryPlugin != null) ra.memory().withPlugin(memoryPlugin);
                if (globalMemory) ra.memory().withGlobalMemory();
                if (decisionPipeline != null) ra.toolExecutor().withPipeline(decisionPipeline);
                if (approvalBroker != null) ra.toolExecutor().withBroker(approvalBroker);
            }
            return new MochaAgent(agent);
        }
    }
}
