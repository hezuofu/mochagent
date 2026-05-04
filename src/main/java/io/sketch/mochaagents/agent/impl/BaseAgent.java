package io.sketch.mochaagents.agent.impl;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.AgentEvent;
import io.sketch.mochaagents.agent.AgentListener;
import io.sketch.mochaagents.agent.AgentMetadata;
import io.sketch.mochaagents.agent.AgentState;
import io.sketch.mochaagents.agent.react.StepResult;
import io.sketch.mochaagents.agent.react.ImprovementPlan;
import io.sketch.mochaagents.agent.react.ReflectionEngine;
import io.sketch.mochaagents.agent.react.SelfCritique;
import io.sketch.mochaagents.context.ContextChunk;
import io.sketch.mochaagents.context.ContextManager;
import io.sketch.mochaagents.evaluation.EvaluationResult;
import io.sketch.mochaagents.evaluation.Evaluator;
import io.sketch.mochaagents.learn.Experience;
import io.sketch.mochaagents.learn.Learner;
import io.sketch.mochaagents.memory.Memory;
import io.sketch.mochaagents.memory.MemoryManager;
import io.sketch.mochaagents.orchestration.Orchestrator;
import io.sketch.mochaagents.perception.LayeredContextBuilder;
import io.sketch.mochaagents.perception.PerceptionObserver;
import io.sketch.mochaagents.perception.PerceptionResult;
import io.sketch.mochaagents.perception.Perceptor;
import io.sketch.mochaagents.plan.Plan;
import io.sketch.mochaagents.plan.PlanStep;
import io.sketch.mochaagents.plan.Planner;
import io.sketch.mochaagents.plan.PlanningRequest;
import io.sketch.mochaagents.reasoning.EffortLevel;
import io.sketch.mochaagents.reasoning.Reasoner;
import io.sketch.mochaagents.reasoning.ReasoningChain;
import io.sketch.mochaagents.reasoning.RecoveryStateMachine;
import io.sketch.mochaagents.reasoning.ThinkingConfig;
import io.sketch.mochaagents.safety.SafetyManager;
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent 基础抽象实现 — Template Method + 可选能力注入.
 *
 * <p>子类覆写 {@link #doExecute(Object, AgentContext)} 实现核心逻辑.
 * 感知/推理/规划/评估/反思/学习等能力通过 Builder 可选注入，null 则跳过.
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 * @author lanxia39@163.com
 */
public abstract class BaseAgent<I, O> implements Agent<I, O> {

    // ============ 核心字段 ============

    protected final String name;
    protected final String description;
    protected final List<AgentListener<I, O>> listeners = new CopyOnWriteArrayList<>();
    protected volatile AgentState state = AgentState.IDLE;

    // ============ 可选能力组件 (null = 未注入，对应钩子为 no-op) ============

    protected final Perceptor<I, O> perceptor;
    protected final Reasoner reasoner;
    protected final Planner<O> planner;
    protected final ToolRegistry toolRegistry;
    protected final SafetyManager safetyManager;
    protected final Evaluator evaluator;
    protected final MemoryManager memoryManager;
    protected final Learner<I, O> learner;
    protected final Orchestrator orchestrator;

    protected final ReflectionEngine reflectionEngine;

    // ============ 集成能力组件 (所有 Agent 自动获得) ============

    /** 恢复状态机 — 处理 prompt-too-long / max_tokens / model error. */
    protected final RecoveryStateMachine recovery;
    /** 分层上下文构建器 — 3 层上下文 + 记忆化. */
    protected final LayeredContextBuilder contextBuilder;
    /** 感知观察器 — 每步环境感知. */
    protected PerceptionObserver perceptionObserver;
    /** 当前 ThinkingConfig — 从 Reasoner 或模型默认解析. */
    protected ThinkingConfig thinkingConfig;
    /** 当前 EffortLevel — 从设置或模型默认解析. */
    protected EffortLevel effortLevel;

    protected BaseAgent(Builder<I, O, ?> builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.perceptor = builder.perceptor;
        this.reasoner = builder.reasoner;
        this.planner = builder.planner;
        this.toolRegistry = builder.toolRegistry;
        this.safetyManager = builder.safetyManager;
        this.evaluator = builder.evaluator;
        this.memoryManager = builder.memoryManager;
        this.learner = builder.learner;
        this.orchestrator = builder.orchestrator;
        this.reflectionEngine = builder.reflectionEngine != null
                ? builder.reflectionEngine : ReflectionEngine.noop();

        // Init integrated components — all agents get these for free
        this.recovery = new RecoveryStateMachine();
        this.contextBuilder = new LayeredContextBuilder(
                Path.of(System.getProperty("user.dir", ".")));
        this.thinkingConfig = builder.thinkingConfig != null
                ? builder.thinkingConfig : ThinkingConfig.adaptive();
        this.effortLevel = builder.effortLevel != null
                ? builder.effortLevel : EffortLevel.HIGH;

        if (perceptor != null) {
            this.perceptionObserver = new PerceptionObserver(contextBuilder, perceptor);
        }
    }

    // ============ Template Method ============

    protected abstract O doExecute(I input, AgentContext ctx);

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

    // ============ 8 步流水线 (子类可选调用) ============

    /** 向后兼容 — 使用显式 ContextManager 执行流水线. */
    public O execute(I input, ContextManager ctx) {
        state = AgentState.RUNNING;
        fireStart(input, null);
        try {
            O result = executePipeline(input, ctx);
            state = AgentState.COMPLETED;
            fireComplete(result, null);
            return result;
        } catch (Exception e) {
            state = AgentState.FAILED;
            fireError(e, null);
            throw e;
        }
    }

    /** 8 步流水线 — 感知→推理→规划→执行→评估→反思. */
    protected O executePipeline(I input, ContextManager ctx) {
        injectMemories(input, ctx);
        perceive(input, ctx);
        ReasoningChain chain = reason(input, ctx);
        Plan<O> plan = plan(input, chain, ctx);
        O output = executePlanSteps(plan, ctx);
        EvaluationResult eval = evaluate(input, output, ctx);
        reflectAndLearn(input, output, eval, ctx);
        ctx.compress();
        return output;
    }

    /** 流水线步骤钩子 — 子类在 executePlanSteps 中被调用. */
    protected O doExecute(I input, ContextManager ctx) {
        return doExecute(input, AgentContext.of(input != null ? input.toString() : ""));
    }

    // ============ 流水线钩子 (inject → null = no-op) ============

    protected void injectMemories(I input, ContextManager ctx) {
        if (memoryManager == null) return;
        for (Memory m : memoryManager.search(input != null ? input.toString() : ""))
            ctx.addChunk(newChunk("memory", m.content()));
    }

    protected void perceive(I input, ContextManager ctx) {
        if (perceptor == null) return;
        PerceptionResult<O> r = perceptor.perceive(input);
        ctx.addChunk(newChunk("perception", r.data() != null ? r.data().toString() : ""));
    }

    protected ReasoningChain reason(I input, ContextManager ctx) {
        if (reasoner == null) return ReasoningChain.empty();
        ReasoningChain chain = reasoner.reason(input != null ? input.toString() : "");
        ctx.addChunk(newChunk("reasoning", chain.summarize()));
        return chain;
    }

    protected Plan<O> plan(I input, ReasoningChain chain, ContextManager ctx) {
        if (planner == null) return null;
        @SuppressWarnings("unchecked")
        O goal = (O) (input != null ? input.toString() : "");
        Plan<O> plan = planner.generatePlan(PlanningRequest.<O>builder()
                .goal(goal).context(chain != null ? chain.summarize() : "").build());
        ctx.addChunk(newChunk("plan", plan.serialize()));
        return plan;
    }

    protected O executePlanSteps(Plan<O> plan, ContextManager ctx) {
        if (plan == null || plan.getSteps().isEmpty()) return doExecute(null, ctx);
        O last = null;
        for (PlanStep step : plan.getSteps()) {
            if (!safetyCheck(step, ctx)) return last;
            if (step.agentId() != null && !step.agentId().isEmpty()
                    && toolRegistry != null && toolRegistry.has(step.agentId()))
                ctx.addChunk(newChunk("tool", String.valueOf(executeTool(step, ctx))));
            @SuppressWarnings("unchecked")
            I si = (I) step.description();
            last = doExecute(si, ctx);
            plan.advance();
        }
        return last;
    }

    protected boolean safetyCheck(PlanStep step, ContextManager ctx) {
        return safetyManager == null || safetyManager.checkContent(step.description());
    }

    protected Object executeTool(PlanStep step, ContextManager ctx) {
        if (toolRegistry == null || !toolRegistry.has(step.agentId())) return "Tool not found";
        try { return toolRegistry.get(step.agentId()).call(step.parameters()); }
        catch (Exception e) { return "Tool error: " + e.getMessage(); }
    }

    protected EvaluationResult evaluate(I input, O output, ContextManager ctx) {
        if (evaluator == null) return null;
        EvaluationResult r = evaluator.evaluate(
                input != null ? input.toString() : "",
                output != null ? output.toString() : "", null);
        ctx.addChunk(newChunk("evaluation", r.toString()));
        return r;
    }

    protected void reflectAndLearn(I input, O output, EvaluationResult eval, ContextManager ctx) {
        if (reflectionEngine != null && eval != null && eval.overallScore() < 0.6) {
            var critique = SelfCritique.builder().analysis(output != null ? output.toString() : "")
                    .needsImprovement(true).suggestion(eval.summary() != null ? eval.summary() : "below").build();
            var improvement = reflectionEngine.reflect(StepResult.builder().build(), critique);
            ctx.addChunk(newChunk("reflection", improvement.summary()));
        }
        if (learner != null) {
            double reward = eval != null ? eval.overallScore() : 0.5;
            learner.learn(Experience.<I, O>builder().input(input).output(output).reward(reward).build());
        }
    }

    // ============ 元数据 / 监听器 ============

    @Override
    public AgentMetadata metadata() {
        return AgentMetadata.builder().name(name).description(description).build();
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

    // ============ 工具方法 ============

    protected static ContextChunk newChunk(String role, String content) {
        int tokens = content != null ? Math.max(1, content.length() / 4) : 1;
        return new ContextChunk(UUID.randomUUID().toString(), role, content, tokens);
    }

    // ============ Builder ============

    @SuppressWarnings("unchecked")
    public abstract static class Builder<I, O, T extends Builder<I, O, T>> {
        protected String name = "base-agent";
        protected String description = "";

        // Capability fields (all null by default = not injected)
        protected Perceptor<I, O> perceptor;
        protected Reasoner reasoner;
        protected Planner<O> planner;
        protected ToolRegistry toolRegistry;
        protected SafetyManager safetyManager;
        protected Evaluator evaluator;
        protected MemoryManager memoryManager;
        protected Learner<I, O> learner;
        protected Orchestrator orchestrator;
        protected ReflectionEngine reflectionEngine;

        // Integrated component config (all agents inherit)
        protected ThinkingConfig thinkingConfig;
        protected EffortLevel effortLevel;

        public T name(String n) { this.name = n; return (T) this; }
        public T description(String d) { this.description = d; return (T) this; }
        public T perceptor(Perceptor<I, O> p) { this.perceptor = p; return (T) this; }
        public T reasoner(Reasoner r) { this.reasoner = r; return (T) this; }
        public T planner(Planner<O> p) { this.planner = p; return (T) this; }
        public T toolRegistry(ToolRegistry r) { this.toolRegistry = r; return (T) this; }
        public T safetyManager(SafetyManager s) { this.safetyManager = s; return (T) this; }
        public T evaluator(Evaluator e) { this.evaluator = e; return (T) this; }
        public T memoryManager(MemoryManager m) { this.memoryManager = m; return (T) this; }
        public T learner(Learner<I, O> l) { this.learner = l; return (T) this; }
        public T orchestrator(Orchestrator l) { this.orchestrator = l; return (T) this; }
        public T reflectionEngine(ReflectionEngine r) { this.reflectionEngine = r; return (T) this; }
        public T thinkingConfig(ThinkingConfig c) { this.thinkingConfig = c; return (T) this; }
        public T effortLevel(EffortLevel e) { this.effortLevel = e; return (T) this; }

        public abstract BaseAgent<I, O> build();
    }
}
