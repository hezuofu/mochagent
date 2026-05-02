package io.sketch.mochaagents.core.impl;

import io.sketch.mochaagents.context.ContextChunk;
import io.sketch.mochaagents.context.ContextManager;
import io.sketch.mochaagents.core.AgentState;
import io.sketch.mochaagents.core.loop.StepResult;
import io.sketch.mochaagents.core.loop.reflection.ImprovementPlan;
import io.sketch.mochaagents.core.loop.reflection.ReflectionEngine;
import io.sketch.mochaagents.core.loop.reflection.SelfCritique;
import io.sketch.mochaagents.evaluation.EvaluationResult;
import io.sketch.mochaagents.evaluation.Evaluator;
import io.sketch.mochaagents.learn.Experience;
import io.sketch.mochaagents.learn.Learner;
import io.sketch.mochaagents.memory.Memory;
import io.sketch.mochaagents.memory.MemoryManager;
import io.sketch.mochaagents.perception.PerceptionResult;
import io.sketch.mochaagents.perception.Perceptor;
import io.sketch.mochaagents.plan.Plan;
import io.sketch.mochaagents.plan.PlanStep;
import io.sketch.mochaagents.plan.Planner;
import io.sketch.mochaagents.plan.PlanningRequest;
import io.sketch.mochaagents.reasoning.Reasoner;
import io.sketch.mochaagents.reasoning.ReasoningChain;
import io.sketch.mochaagents.safety.SafetyManager;
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolRegistry;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 能力完备型 Agent 抽象基类 — 将感知、推理、规划、工具、安全、评估、
 * 记忆、学习等能力通过 Builder 注入，Context 作为显式参数贯穿执行.
 *
 * <p>子类只需覆写 {@link #doExecute(Object, ContextManager)} 实现领域逻辑.
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * CapableAgent<String, String> agent = CapableAgent.builder()
 *     .name("my-agent")
 *     .perceptor(new CodebasePerceptor())
 *     .reasoner(reasoner)
 *     .planner(new DynamicPlanner())
 *     .toolRegistry(registry)
 *     .safetyManager(safety)
 *     .evaluator(new LLMJudge(llm))
 *     .memoryManager(memory)
 *     .learner(new FewShotLearner())
 *     .build();
 *
 * ContextManager ctx = new ContextManager(8192, new SlidingWindowStrategy(), null);
 * String result = agent.execute("修复 NPE", ctx);
 * }</pre>
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public abstract class CapableAgent<I, O> extends BaseAgent<I, O> {

    // ============ 能力组件 ============

    protected final Perceptor<I, O> perceptor;
    protected final Reasoner reasoner;
    protected final Planner<O> planner;
    protected final ToolRegistry toolRegistry;
    protected final SafetyManager safetyManager;
    protected final Evaluator evaluator;
    protected final MemoryManager memoryManager;
    protected final Learner<I, O> learner;
    protected final ReflectionEngine reflectionEngine;

    protected CapableAgent(Builder<I, O, ?> builder) {
        super(builder);
        this.perceptor = builder.perceptor;
        this.reasoner = builder.reasoner;
        this.planner = builder.planner;
        this.toolRegistry = builder.toolRegistry;
        this.safetyManager = builder.safetyManager;
        this.evaluator = builder.evaluator;
        this.memoryManager = builder.memoryManager;
        this.learner = builder.learner;
        this.reflectionEngine = builder.reflectionEngine;
    }

    // ============ 核心执行（Context 显式参数） ============

    /**
     * 带 Context 的完整执行 — 编排感知→推理→规划→安全→工具→评估→反思 全流程.
     */
    public O execute(I input, ContextManager ctx) {
        state = AgentState.RUNNING;
        fireStart(input);

        try {
            // 1. 注入历史记忆
            injectMemories(input, ctx);

            // 2. 感知
            perceive(input, ctx);

            // 3. 推理
            ReasoningChain chain = reason(input, ctx);

            // 4. 规划
            Plan<O> plan = plan(input, chain, ctx);

            // 5. 执行计划步骤
            O output = executePlanSteps(plan, ctx);

            // 6. 评估
            EvaluationResult eval = evaluate(input, output, ctx);

            // 7. 反思 + 学习
            reflectAndLearn(input, output, eval, ctx);

            // 8. 压缩上下文
            ctx.compress();

            state = AgentState.COMPLETED;
            fireComplete(output);
            return output;

        } catch (Exception e) {
            state = AgentState.FAILED;
            fireError(e);
            throw e;
        }
    }

    /** 向后兼容 — 使用默认 ContextManager. */
    @Override
    public O execute(I input) {
        return execute(input, new ContextManager(8192,
                (chunks, maxT) -> chunks, null));
    }

    @Override
    public CompletableFuture<O> executeAsync(I input) {
        return CompletableFuture.supplyAsync(() -> execute(input));
    }

    // ============ 子类钩子 ============

    /**
     * 子类实现核心领域逻辑.
     * <p>在安全校验通过后、工具调用之后被调用.
     */
    protected abstract O doExecute(I input, ContextManager ctx);

    /** 向后兼容钩子. */
    @Override
    protected O doExecute(I input) {
        return doExecute(input, new ContextManager(8192,
                (chunks, maxT) -> chunks, null));
    }

    // ============ 流水线步骤（子类可覆写定制） ============

    /** 从记忆系统检索相关历史注入 Context. */
    protected void injectMemories(I input, ContextManager ctx) {
        if (memoryManager == null) return;
        String query = input != null ? input.toString() : "";
        List<Memory> relevant = memoryManager.search(query);
        for (Memory m : relevant) {
            ctx.addChunk(newChunk("memory", m.content()));
        }
    }

    /** 感知环境，结果写入 Context. */
    protected void perceive(I input, ContextManager ctx) {
        if (perceptor == null) return;
        PerceptionResult<O> result = perceptor.perceive(input);
        String data = result.data() != null ? result.data().toString() : "";
        ctx.addChunk(newChunk("perception", data));
    }

    /** 推理分析，链写入 Context. */
    protected ReasoningChain reason(I input, ContextManager ctx) {
        if (reasoner == null) return ReasoningChain.empty();
        String question = input != null ? input.toString() : "";
        ReasoningChain chain = reasoner.reason(question);
        ctx.addChunk(newChunk("reasoning", chain.summarize()));
        return chain;
    }

    /** 生成或更新计划，写入 Context. */
    protected Plan<O> plan(I input, ReasoningChain chain, ContextManager ctx) {
        if (planner == null) return null;
        @SuppressWarnings("unchecked")
        O goal = (O) (input != null ? input.toString() : "");
        PlanningRequest<O> request = PlanningRequest.<O>builder()
                .goal(goal)
                .context(chain != null ? chain.summarize() : "")
                .build();
        Plan<O> plan = planner.generatePlan(request);
        ctx.addChunk(newChunk("plan", plan.serialize()));
        return plan;
    }

    /** 逐步骤执行计划. */
    protected O executePlanSteps(Plan<O> plan, ContextManager ctx) {
        if (plan == null || plan.getSteps().isEmpty()) {
            return doExecute(null, ctx);
        }

        O lastOutput = null;
        for (PlanStep step : plan.getSteps()) {
            // 安全检查
            if (!safetyCheck(step, ctx)) {
                return lastOutput;
            }

            // 工具调用（根据步骤 agentId 匹配 Tool）
            if (step.agentId() != null && !step.agentId().isEmpty()
                    && toolRegistry != null && toolRegistry.has(step.agentId())) {
                Object toolResult = executeTool(step, ctx);
                ctx.addChunk(newChunk("tool", toolResult != null ? toolResult.toString() : ""));
            }

            // Agent 执行业务逻辑
            @SuppressWarnings("unchecked")
            I stepInput = (I) step.description();
            lastOutput = doExecute(stepInput, ctx);

            plan.advance();
        }
        return lastOutput;
    }

    /** 安全检查. */
    protected boolean safetyCheck(PlanStep step, ContextManager ctx) {
        if (safetyManager == null) return true;
        return safetyManager.checkContent(step.description());
    }

    /** 执行工具. */
    protected Object executeTool(PlanStep step, ContextManager ctx) {
        if (toolRegistry == null || !toolRegistry.has(step.agentId())) {
            return "Tool not found: " + step.agentId();
        }
        Tool tool = toolRegistry.get(step.agentId());
        try {
            return tool.call(step.parameters());
        } catch (Exception e) {
            return "Tool error: " + e.getMessage();
        }
    }

    /** 评估输出质量. */
    protected EvaluationResult evaluate(I input, O output, ContextManager ctx) {
        if (evaluator == null) return null;
        String in = input != null ? input.toString() : "";
        String out = output != null ? output.toString() : "";
        EvaluationResult eval = evaluator.evaluate(in, out, null);
        ctx.addChunk(newChunk("evaluation", eval.toString()));
        return eval;
    }

    /** 反思不足 + 从经验中学习. */
    protected void reflectAndLearn(I input, O output, EvaluationResult eval, ContextManager ctx) {
        // 反思
        if (reflectionEngine != null && eval != null && eval.overallScore() < 0.6) {
            SelfCritique critique = SelfCritique.builder()
                    .analysis(output != null ? output.toString() : "")
                    .needsImprovement(true)
                    .suggestion(eval.summary() != null ? eval.summary() : "below threshold")
                    .build();
            ImprovementPlan improvement = reflectionEngine.reflect(
                    StepResult.builder().build(), critique);
            ctx.addChunk(newChunk("reflection", improvement.summary()));
        }

        // 学习
        if (learner != null) {
            double reward = eval != null ? eval.overallScore() : 0.5;
            Experience<I, O> exp = Experience.<I, O>builder()
                    .input(input).output(output).reward(reward).build();
            learner.learn(exp);
        }
    }

    // ============ 内部工具方法 ============

    /** 创建 ContextChunk，token 数量按内容长度 / 4 估算. */
    private static ContextChunk newChunk(String role, String content) {
        int tokens = content != null ? Math.max(1, content.length() / 4) : 1;
        return new ContextChunk(UUID.randomUUID().toString(), role, content, tokens);
    }

    // ============ Builder ============

    /**
     * CapableAgent Builder — 支持链式注入所有能力组件.
     */
    @SuppressWarnings("unchecked")
    public abstract static class Builder<I, O, T extends Builder<I, O, T>>
            extends BaseAgent.Builder<I, O, T> {

        protected Perceptor<I, O> perceptor;
        protected Reasoner reasoner;
        protected Planner<O> planner;
        protected ToolRegistry toolRegistry;
        protected SafetyManager safetyManager;
        protected Evaluator evaluator;
        protected MemoryManager memoryManager;
        protected Learner<I, O> learner;
        protected ReflectionEngine reflectionEngine = ReflectionEngine.noop();

        public T perceptor(Perceptor<I, O> perceptor) {
            this.perceptor = perceptor; return (T) this;
        }

        public T reasoner(Reasoner reasoner) {
            this.reasoner = reasoner; return (T) this;
        }

        public T planner(Planner<O> planner) {
            this.planner = planner; return (T) this;
        }

        public T toolRegistry(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry; return (T) this;
        }

        public T safetyManager(SafetyManager safetyManager) {
            this.safetyManager = safetyManager; return (T) this;
        }

        public T evaluator(Evaluator evaluator) {
            this.evaluator = evaluator; return (T) this;
        }

        public T memoryManager(MemoryManager memoryManager) {
            this.memoryManager = memoryManager; return (T) this;
        }

        public T learner(Learner<I, O> learner) {
            this.learner = learner; return (T) this;
        }

        public T reflectionEngine(ReflectionEngine reflectionEngine) {
            this.reflectionEngine = reflectionEngine; return (T) this;
        }
    }
}
