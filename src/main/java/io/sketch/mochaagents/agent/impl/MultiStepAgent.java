package io.sketch.mochaagents.agent.impl;

import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.loop.MemoryProvider;
import io.sketch.mochaagents.agent.loop.SystemPromptProvider;
import io.sketch.mochaagents.agent.loop.TerminationCondition;
import io.sketch.mochaagents.agent.loop.strategy.ReActLoop;
import io.sketch.mochaagents.context.ContextChunk;
import io.sketch.mochaagents.context.ContextManager;
import io.sketch.mochaagents.evaluation.EvaluationResult;
import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import io.sketch.mochaagents.memory.AgentMemory;
import io.sketch.mochaagents.agent.loop.step.*;
import io.sketch.mochaagents.perception.PerceptionResult;
import io.sketch.mochaagents.plan.Plan;
import io.sketch.mochaagents.plan.PlanningRequest;
import io.sketch.mochaagents.prompt.PromptTemplate;
import io.sketch.mochaagents.reasoning.ReasoningChain;
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * MultiStepAgent — ReAct 循环的抽象基类.
 *
 * <p>封装 "思考→行动→观察" 主循环，子类实现 {@link #executeReActStep(int, AgentMemory)}
 * 提供不同的动作执行策略。
 *
 * <p>对应 smolagents 的 {@code MultiStepAgent}.
 *
 * <h3>子类</h3>
 * <ul>
 *   <li>{@link ToolCallingAgent} — 使用 LLM 原生 tool-calling API</li>
 *   <li>{@link CodeAgent} — 解析并执行 LLM 输出的代码块</li>
 * </ul>
 * @author lanxia39@163.com
 */
public abstract class MultiStepAgent extends CapableAgent<String, String>
        implements MemoryProvider, SystemPromptProvider {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    // ============ ReAct 核心组件 ============

    protected final LLM llm;
    protected final AgentMemory memory = new AgentMemory();
    protected final int maxSteps;
    protected final int planningInterval;
    protected final boolean addBaseTools;
    protected final Map<String, MultiStepAgent> managedAgents = new LinkedHashMap<>();

    // ============ Context ============

    private ContextManager contextManager;

    protected ContextManager contextManager() {
        if (contextManager == null) {
            contextManager = new ContextManager(8192,
                    (chunks, maxT) -> chunks, null);
        }
        return contextManager;
    }

    // ============ Prompt 模板 ============

    protected PromptTemplate systemPromptTemplate = PromptTemplate.of("");
    protected PromptTemplate planningPromptTemplate;
    protected PromptTemplate finalAnswerPreTemplate;
    protected PromptTemplate finalAnswerPostTemplate;

    protected MultiStepAgent(Builder<?> builder) {
        super(builder);
        this.llm = builder.llm;
        this.maxSteps = builder.maxSteps;
        this.planningInterval = builder.planningInterval;
        this.addBaseTools = builder.addBaseTools;
        this.systemPromptTemplate = builder.systemPromptTemplate != null
                ? builder.systemPromptTemplate : PromptTemplate.of("");
        this.planningPromptTemplate = builder.planningPromptTemplate;
        this.finalAnswerPreTemplate = builder.finalAnswerPreTemplate;
        this.finalAnswerPostTemplate = builder.finalAnswerPostTemplate;
        setupManagedAgents(builder.managedAgents);
        setupTools(builder.tools);
    }

    // ============ Public API ============

    /** 获取 AgentMemory. */
    public AgentMemory memory() {
        return memory;
    }

    /** 构建系统提示（子类覆写）. */
    public String buildSystemPrompt() {
        return systemPromptTemplate.render(Map.of(
                "tools", formatTools(),
                "managed_agents", formatManagedAgents(),
                "instructions", description != null ? description : ""
        ));
    }

    // ============ 执行入口 ============

    /** 运行 ReAct 循环完成任务（向后兼容）. */
    public String run(String task) {
        return run(AgentContext.of(task), maxSteps);
    }

    /** 运行 ReAct 循环，指定最大步数（向后兼容）. */
    public String run(String task, int maxSteps) {
        return run(AgentContext.of(task), maxSteps);
    }

    /** 运行 ReAct 循环 — 以 AgentContext 承载会话/对话历史/元数据. */
    public String run(AgentContext ctx) {
        return run(ctx, maxSteps);
    }

    /** 运行 ReAct 循环 — AgentContext + 指定最大步数（主入口）. */
    public String run(AgentContext ctx, int maxSteps) {
        long startMs = System.currentTimeMillis();
        String task = ctx.userMessage();
        log.info("Agent '{}' starting, session={}, user={}, maxSteps={}, task={}",
                name, ctx.sessionId(), ctx.userId(), maxSteps, truncate(task, 120));

        // 构建系统提示（可被 ctx.metadata 覆盖）
        String systemPrompt = buildSystemPrompt();
        systemPrompt = enrichFromContext(systemPrompt, ctx);

        memory.reset(systemPrompt);
        memory.appendTask(task);

        // 注入对话历史到 AgentMemory
        injectConversationHistory(ctx);

        // ===== Pre-loop hooks =====
        ContextManager ctxMgr = contextManager();
        injectMemories(task, ctxMgr);
        perceiveAndRemember(task, ctxMgr);
        ReasoningChain chain = reason(task, ctxMgr);
        planAndRemember(task, chain, ctxMgr);

        // ===== Core ReAct loop =====
        ReActLoop<String, String> loop = new ReActLoop<>(
                (ReActLoop.PlanningFn<String>) this::planStep,
                (ReActLoop.StepExecutor<String>) this::executeReActStep,
                planningInterval
        );

        TerminationCondition condition = TerminationCondition.maxSteps(maxSteps)
                .or(TerminationCondition.onError());

        String result = loop.run(this, task, condition);

        if (!memory.hasFinalAnswer()) {
            log.warn("Agent '{}' exceeded max steps, providing fallback answer", name);
            result = provideFinalAnswer(task);
            memory.appendFinalAnswer(result);
        }

        // ===== Post-loop hooks =====
        EvaluationResult eval = evaluate(task, result, ctxMgr);
        reflectAndLearn(task, result, eval, ctxMgr);
        ctxMgr.compress();

        long elapsed = System.currentTimeMillis() - startMs;
        log.info("Agent '{}' completed in {}ms, steps={}, result={}",
                name, elapsed, memory.steps().size(), truncate(result, 300));
        return result;
    }

    // ============ 抽象方法（CapableAgent + 子类） ============

    // ============ 实现 BaseAgent / CapableAgent 钩子 ============

    /**
     * BaseAgent 主入口 — 直接委托给 {@link #run(AgentContext)}.
     * 绕过 CapableAgent 的 8 步流水线，使用 ReAct 循环.
     */
    @Override
    protected String doExecute(String input, AgentContext actx) {
        return run(actx);
    }

    /**
     * CapableAgent 流水线钩子 — 向后兼容.
     */
    @Override
    protected String doExecute(String input, io.sketch.mochaagents.context.ContextManager ctx) {
        return run(AgentContext.of(input));
    }

    /**
     * 执行单次 ReAct 步骤.
     *
     * <p>子类（ToolCallingAgent / CodeAgent）实现具体的 LLM 调用与工具/代码执行逻辑。
     *
     * @param stepNumber 当前步号
     * @param input      原始输入/任务
     * @param memory     Agent 记忆
     * @return StepResult 包含观察、输出和终止状态
     */
    protected abstract io.sketch.mochaagents.agent.loop.StepResult executeReActStep(
            int stepNumber, String input, AgentMemory memory);

    // ============ 内部方法 ============

    /** 规划步骤（可选）. */
    protected String planStep(int stepNumber, String input, AgentMemory memory) {
        if (planningPromptTemplate == null) return null;

        log.debug("Agent '{}' planning at step {}", name, stepNumber);

        String prompt = planningPromptTemplate.render(Map.of(
                "task", input != null ? input : "",
                "step", String.valueOf(stepNumber),
                "tools", formatTools()
        ));

        LLMRequest request = LLMRequest.builder()
                .addMessage("user", prompt)
                .maxTokens(1024)
                .build();

        try {
            LLMResponse response = llm.complete(request);
            log.debug("Agent '{}' plan generated: {}", name, truncate(response.content(), 150));
            return response.content();
        } catch (Exception e) {
            log.error("Agent '{}' planning failed at step {}", name, stepNumber, e);
            return null;
        }
    }

    /** 感知环境并将结果写入 AgentMemory，使 LLM 可见. */
    private void perceiveAndRemember(String input, ContextManager ctx) {
        if (perceptor == null) return;
        PerceptionResult<String> result = perceptor.perceive(input);
        String data = result.data() != null ? result.data().toString() : "";
        if (!data.isEmpty()) {
            memory.append(ContentStep.systemPrompt("[Perception]:\n" + data));
        }
        ctx.addChunk(newChunk("perception", data));
    }

    /** 生成计划并将结果写入 AgentMemory 作为 PlanningStep. */
    private void planAndRemember(String input, ReasoningChain chain, ContextManager ctx) {
        if (planner == null) return;
        Plan<String> plan = planner.generatePlan(
                PlanningRequest.<String>builder()
                        .goal(input)
                        .context(chain != null ? chain.summarize() : "")
                        .build());
        if (plan != null && !plan.getSteps().isEmpty()) {
            memory.appendPlanning(plan.serialize(), "", 0, 0);
        }
        ctx.addChunk(newChunk("plan", plan.serialize()));
    }

    /** 将 AgentContext 中的对话历史注入 AgentMemory. */
    private void injectConversationHistory(AgentContext ctx) {
        String history = ctx.conversationHistory();
        if (history == null || history.isEmpty()) return;

        // 尝试按行解析为 alternating user/assistant 消息
        String[] lines = history.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("User: ") || line.startsWith("user: ")) {
                memory.append(ContentStep.task(line.substring(line.indexOf(' ') + 1).trim()));
            } else if (line.startsWith("Assistant: ") || line.startsWith("assistant: ")) {
                memory.append(new ActionStep(memory.size() + 1, "",
                        line.substring(line.indexOf(' ') + 1).trim(),
                        "history", "", null, 0, 0, false));
            }
        }
    }

    /** 用 AgentContext.metadata 增强系统提示. */
    private String enrichFromContext(String systemPrompt, AgentContext ctx) {
        StringBuilder sb = new StringBuilder(systemPrompt);
        Map<String, Object> meta = ctx.metadata();
        if (meta != null) {
            for (var entry : meta.entrySet()) {
                if ("instructions".equals(entry.getKey())) {
                    sb.append("\n\n[Instructions]: ").append(entry.getValue());
                } else if ("role".equals(entry.getKey())) {
                    sb.append("\n\n[Role]: ").append(entry.getValue());
                }
            }
        }
        if (ctx.sessionId() != null && !ctx.sessionId().isEmpty()) {
            sb.append("\n[Session: ").append(ctx.sessionId()).append("]");
        }
        return sb.toString();
    }

    /** 超出最大步数时的兜底答案. */
    protected String provideFinalAnswer(String task) {
        if (finalAnswerPreTemplate == null || finalAnswerPostTemplate == null) {
            log.debug("Agent '{}' no fallback templates configured", name);
            return "Unable to complete task within step limit.";
        }

        log.debug("Agent '{}' generating fallback answer via LLM", name);
        String preMsg = finalAnswerPreTemplate.render(Map.of());
        String postMsg = finalAnswerPostTemplate.render("task", task);

        LLMRequest request = LLMRequest.builder()
                .addMessage("system", preMsg)
                .addMessage("user", postMsg)
                .maxTokens(512)
                .build();

        try {
            String answer = llm.complete(request).content();
            log.debug("Agent '{}' fallback answer: {}", name, truncate(answer, 100));
            return answer;
        } catch (Exception e) {
            log.error("Agent '{}' fallback answer generation failed", name, e);
            return "Error generating final answer: " + e.getMessage();
        }
    }

    /** 将记忆转换为 LLM 消息列表. */
    protected List<Map<String, String>> writeMemoryToMessages() {
        List<Map<String, String>> messages = new ArrayList<>();

        // System prompt
        if (memory.systemPrompt() != null && !memory.systemPrompt().isEmpty()) {
            messages.add(Map.of("role", "system", "content", memory.systemPrompt()));
        }

        // 各步骤
        int stepCount = memory.steps().size();
        for (MemoryStep step : memory.steps()) {
            if (step instanceof ContentStep cs && cs.isSystemPrompt()) {
                messages.add(Map.of("role", "system", "content", cs.text()));
            } else if (step instanceof ContentStep cs && cs.isTask()) {
                messages.add(Map.of("role", "user", "content", cs.text()));
            } else if (step instanceof PlanningStep ps) {
                messages.add(Map.of("role", "assistant", "content", "Plan:\n" + ps.plan()));
            } else if (step instanceof ActionStep as) {
                if (as.modelInput() != null && !as.modelInput().isEmpty()) {
                    messages.add(Map.of("role", "assistant", "content", as.modelOutput()));
                }
                if (as.observation() != null && !as.observation().isEmpty()) {
                    messages.add(Map.of("role", "user",
                            "content", "Observation:\n" + as.observation()));
                }
            }
            // ContentStep(final_answer) — 不加入消息
        }

        log.debug("Agent '{}' built {} LLM messages from {} steps", name, messages.size(), stepCount);
        return messages;
    }

    // ============ 初始化辅助 ============

    private void setupManagedAgents(List<MultiStepAgent> agents) {
        if (agents == null) return;
        for (MultiStepAgent a : agents) {
            managedAgents.put(a.name, a);
        }
    }

    private void setupTools(List<Tool> tools) {
        if (toolRegistry == null) return;
        if (tools != null) {
            tools.forEach(toolRegistry::register);
        }
        // 始终注册 final_answer 工具
        if (!toolRegistry.has("final_answer")) {
            toolRegistry.register(new FinalAnswerTool());
        }
    }

    // ============ 格式化方法 ============

    protected String formatTools() {
        if (toolRegistry == null) return "None";
        StringBuilder sb = new StringBuilder();
        for (Tool t : toolRegistry.all()) {
            sb.append("- ").append(t.getName()).append(": ").append(t.getDescription()).append("\n");
        }
        return sb.toString();
    }

    protected String formatManagedAgents() {
        if (managedAgents.isEmpty()) return "None";
        StringBuilder sb = new StringBuilder();
        for (var entry : managedAgents.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue().description).append("\n");
        }
        return sb.toString();
    }

    // ============ FinalAnswerTool（内部工具） ============

    static final class FinalAnswerTool implements Tool {
        @Override public String getName() { return "final_answer"; }
        @Override public String getDescription() { return "Provides a final answer to the given problem."; }
        @Override public Map<String, ToolInput> getInputs() {
            return Map.of("answer", ToolInput.any("The final answer to the problem"));
        }
        @Override public String getOutputType() { return "any"; }
        @Override public Object call(Map<String, Object> arguments) {
            return arguments.getOrDefault("answer", "");
        }
        @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
    }

    // ============ 工具方法 ============

    protected static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ============ Builder ============

    @SuppressWarnings("unchecked")
    public abstract static class Builder<T extends Builder<T>>
            extends CapableAgent.Builder<String, String, T> {

        protected LLM llm;
        protected List<Tool> tools = new ArrayList<>();
        protected List<MultiStepAgent> managedAgents = new ArrayList<>();
        protected int maxSteps = 20;
        protected int planningInterval;
        protected boolean addBaseTools;
        protected PromptTemplate systemPromptTemplate;
        protected PromptTemplate planningPromptTemplate;
        protected PromptTemplate finalAnswerPreTemplate;
        protected PromptTemplate finalAnswerPostTemplate;

        public T llm(LLM llm) { this.llm = llm; return (T) this; }
        public T tools(List<Tool> tools) { this.tools = tools; return (T) this; }
        public T managedAgents(List<MultiStepAgent> agents) { this.managedAgents = agents; return (T) this; }
        public T maxSteps(int maxSteps) { this.maxSteps = maxSteps; return (T) this; }
        public T planningInterval(int interval) { this.planningInterval = interval; return (T) this; }
        public T addBaseTools(boolean add) { this.addBaseTools = add; return (T) this; }
        public T systemPromptTemplate(PromptTemplate t) { this.systemPromptTemplate = t; return (T) this; }
        public T planningPromptTemplate(PromptTemplate t) { this.planningPromptTemplate = t; return (T) this; }
        public T finalAnswerPreTemplate(PromptTemplate t) { this.finalAnswerPreTemplate = t; return (T) this; }
        public T finalAnswerPostTemplate(PromptTemplate t) { this.finalAnswerPostTemplate = t; return (T) this; }
    }
}
