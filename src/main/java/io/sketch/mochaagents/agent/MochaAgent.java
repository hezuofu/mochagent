package io.sketch.mochaagents.agent;

import io.sketch.mochaagents.agent.impl.*;
import io.sketch.mochaagents.agent.react.*;
import io.sketch.mochaagents.agent.react.strategy.ReflexionLoop;
import io.sketch.mochaagents.agent.react.strategy.ReWOOLoop;
import io.sketch.mochaagents.evaluation.Evaluator;
import io.sketch.mochaagents.llm.*;
import io.sketch.mochaagents.memory.MemoryManager;
import io.sketch.mochaagents.orchestration.Orchestrator;
import io.sketch.mochaagents.perception.Perceptor;
import io.sketch.mochaagents.plan.Planner;
import io.sketch.mochaagents.prompt.PromptTemplate;
import io.sketch.mochaagents.reasoning.*;
import io.sketch.mochaagents.safety.SafetyManager;
import io.sketch.mochaagents.tool.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * MochaAgent — unified facade implementing {@link Agent}, composable in orchestrator chains.
 *
 * <h2>Quick start</h2>
 * <pre>{@code
 * MochaAgent agent = MochaAgent.builder()
 *     .name("assistant").llm(llm).addTool(new WebSearchTool()).build();
 * String result = agent.run("What is the weather?");
 *
 * // Composable: participate in Agent chains and orchestrator teams
 * MochaAgent a1 = MochaAgent.builder().name("a1").llm(llm).build();
 * MochaAgent a2 = MochaAgent.builder().name("a2").llm(llm).build();
 * Agent<String, String> pipeline = a1.andThen(a2);
 * }</pre>
 *
 * @author lanxia39@163.com
 */
public final class MochaAgent implements Agent<String, String> {

    private final Agent<String, String> inner;

    private MochaAgent(Agent<String, String> inner) {
        this.inner = inner;
    }

    // ============ Agent interface ============

    @Override
    public String execute(String input, AgentContext ctx) {
        return inner.execute(input, ctx);
    }

    @Override
    public String execute(String input) {
        return inner.execute(input);
    }

    @Override
    public CompletableFuture<String> executeAsync(String input, AgentContext ctx) {
        return inner.executeAsync(input, ctx);
    }

    @Override
    public AgentMetadata metadata() { return inner.metadata(); }

    @Override
    public void addListener(AgentListener<String, String> l) { inner.addListener(l); }

    @Override
    public void removeListener(AgentListener<String, String> l) { inner.removeListener(l); }

    // ============ Convenience ============

    public String run(String task) { return execute(task); }

    public ExecutionReport runAndReport(String task) {
        if (inner instanceof ReActAgent ra) return ra.runAndReport(task);
        long start = System.currentTimeMillis();
        String result = run(task);
        return new ExecutionReport(result, 1, System.currentTimeMillis() - start,
                0, 0, 0, List.of(), "Completed");
    }

    public String runStreaming(AgentContext ctx, java.util.function.Consumer<String> onToken) {
        if (inner instanceof ReActAgent ra) return ra.runStreaming(ctx, onToken);
        return execute(ctx.userMessage(), ctx);
    }

    public Agent<String, String> inner() { return inner; }

    /** Switch execution paradigm at runtime. */
    public MochaAgent withLoop(AgenticLoop<String, String> loop) {
        if (inner instanceof ReActAgent ra) return new MochaAgent(ra.withAgenticLoop(loop));
        return this;
    }

    // ============ Factory ============

    public static MochaAgent create(Config config) {
        return new MochaAgent(buildFromConfig(config));
    }

    @SuppressWarnings("unchecked")
    private static Agent<String, String> buildFromConfig(Config config) {
        ReActAgent.Builder<?> builder = switch (config.agentType) {
            case TOOL_CALLING -> ToolCallingAgent.builder();
            case CODE -> CodeAgent.builder();
        };

        builder.name(config.name).description(config.description)
                .llm(config.llm).maxSteps(config.maxSteps);

        if (config.toolRegistry != null) builder.toolRegistry(config.toolRegistry);
        if (config.tools != null && !config.tools.isEmpty())
            builder.tools(new ArrayList<>(config.tools));

        if (config.thinkingConfig != null) builder.thinkingConfig(config.thinkingConfig);
        if (config.effortLevel != null) builder.effortLevel(config.effortLevel);
        if (config.perceptor != null) builder.perceptor(config.perceptor);
        if (config.reasoner != null) builder.reasoner(config.reasoner);
        if (config.planner != null) builder.planner(config.planner);
        if (config.evaluator != null) builder.evaluator(config.evaluator);
        if (config.memoryManager != null) builder.memoryManager(config.memoryManager);
        if (config.safetyManager != null) builder.safetyManager(config.safetyManager);
        if (config.loop != null) builder.agenticLoop(config.loop);
        if (config.orchestrator != null) builder.orchestrator(config.orchestrator);
        if (config.systemPrompt != null)
            builder.systemPromptTemplate(PromptTemplate.of(config.systemPrompt));

        return (Agent<String, String>) builder.build();
    }

    // ============ Builder ============

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final Config config = new Config();

        public Builder name(String name) { config.name = name; return this; }
        public Builder description(String d) { config.description = d; return this; }
        public Builder llm(LLM llm) { config.llm = llm; return this; }
        public Builder maxSteps(int n) { config.maxSteps = n; return this; }
        public Builder systemPrompt(String sp) { config.systemPrompt = sp; return this; }
        public Builder toolCalling() { config.agentType = Config.AgentType.TOOL_CALLING; return this; }
        public Builder code() { config.agentType = Config.AgentType.CODE; return this; }
        public Builder toolRegistry(ToolRegistry reg) { config.toolRegistry = reg; return this; }
        public Builder addTool(Tool tool) {
            if (config.tools == null) config.tools = new ArrayList<>();
            config.tools.add(tool); return this;
        }
        public Builder thinkingConfig(ThinkingConfig tc) { config.thinkingConfig = tc; return this; }
        public Builder effortLevel(EffortLevel e) { config.effortLevel = e; return this; }
        public Builder perceptor(Perceptor<String, String> p) { config.perceptor = p; return this; }
        public Builder reasoner(Reasoner r) { config.reasoner = r; return this; }
        public Builder planner(Planner<String> p) { config.planner = p; return this; }
        public Builder evaluator(Evaluator e) { config.evaluator = e; return this; }
        public Builder memoryManager(MemoryManager m) { config.memoryManager = m; return this; }
        public Builder safetyManager(SafetyManager s) { config.safetyManager = s; return this; }
        public Builder loop(AgenticLoop<String, String> l) { config.loop = l; return this; }
        public Builder orchestrator(Orchestrator o) { config.orchestrator = o; return this; }

        /** Convenience: use Reflexion loop (self-critique after each step). */
        public Builder reflexionLoop() {
            config.loop = new ReflexionLoop<>(null, ReflectionEngine.noop()); return this;
        }
        /** Convenience: use ReWOO loop (reason fully, then batch execute). */
        public Builder rewooLoop(ReWOOLoop.Reasoner reasoner, ReWOOLoop.ToolExecutor executor,
                                  ReWOOLoop.Synthesizer synth) {
            config.loop = new ReWOOLoop<>(reasoner, executor, synth); return this;
        }

        public MochaAgent build() {
            if (config.llm == null) config.llm = new FallbackLLM();
            if (config.name == null) config.name = "mocha-agent";
            return new MochaAgent(buildFromConfig(config));
        }
    }

    // ============ Config ============

    public static final class Config {
        public enum AgentType { TOOL_CALLING, CODE }

        AgentType agentType = AgentType.TOOL_CALLING;
        String name;
        String description = "";
        LLM llm;
        int maxSteps = 20;
        String systemPrompt;
        ToolRegistry toolRegistry;
        List<Tool> tools;
        ThinkingConfig thinkingConfig;
        EffortLevel effortLevel;
        Perceptor<String, String> perceptor;
        Reasoner reasoner;
        Planner<String> planner;
        Evaluator evaluator;
        MemoryManager memoryManager;
        SafetyManager safetyManager;
        AgenticLoop<String, String> loop;
        Orchestrator orchestrator;

        // Setters for cross-package access (PluginLoader etc.)
        public void setPerceptor(Perceptor<String, String> p) { this.perceptor = p; }
        public void setReasoner(Reasoner r) { this.reasoner = r; }
        @SuppressWarnings("unchecked")
        public void setPlanner(io.sketch.mochaagents.plan.Planner<?> p) { this.planner = (Planner<String>) p; }
        public void setEvaluator(Evaluator e) { this.evaluator = e; }
        public void setLoop(AgenticLoop<String, String> l) { this.loop = l; }
    }
}
