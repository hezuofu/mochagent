package io.sketch.mochaagents.agent.impl;

import io.sketch.mochaagents.agent.loop.TerminationCondition;
import io.sketch.mochaagents.agent.loop.strategy.ReActLoop;
import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import io.sketch.mochaagents.memory.AgentMemory;
import io.sketch.mochaagents.agent.loop.step.*;
import io.sketch.mochaagents.prompt.PromptTemplate;
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;

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
 */
public abstract class MultiStepAgent extends CapableAgent<String, String> {

    // ============ ReAct 核心组件 ============

    protected final LLM llm;
    protected final AgentMemory memory = new AgentMemory();
    protected final int maxSteps;
    protected final int planningInterval;
    protected final boolean addBaseTools;
    protected final Map<String, MultiStepAgent> managedAgents = new LinkedHashMap<>();

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

    /** 运行 ReAct 循环完成任务. */
    public String run(String task) {
        return run(task, maxSteps);
    }

    /** 运行 ReAct 循环（指定最大步数）. */
    public String run(String task, int maxSteps) {
        memory.reset(buildSystemPrompt());
        memory.appendTask(task);

        ReActLoop<String, String> loop = new ReActLoop<>(
                (ReActLoop.PlanningFn<String>) this::planStep,
                (ReActLoop.StepExecutor<String>) this::executeReActStep,
                planningInterval
        );

        TerminationCondition condition = TerminationCondition.maxSteps(maxSteps)
                .or(TerminationCondition.onError());

        String result = loop.run(this, task, condition);

        // 超出步数时提供兜底答案
        if (!memory.hasFinalAnswer()) {
            result = provideFinalAnswer(task);
            memory.appendFinalAnswer(result);
        }

        return result;
    }

    // ============ 抽象方法（CapableAgent + 子类） ============

    /**
     * 实现 CapableAgent 的抽象方法 — 委托给 ReAct run().
     */
    @Override
    protected String doExecute(String input, io.sketch.mochaagents.context.ContextManager ctx) {
        return run(input);
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
            return response.content();
        } catch (Exception e) {
            return null;
        }
    }

    /** 超出最大步数时的兜底答案. */
    protected String provideFinalAnswer(String task) {
        if (finalAnswerPreTemplate == null || finalAnswerPostTemplate == null) {
            return "Unable to complete task within step limit.";
        }

        String preMsg = finalAnswerPreTemplate.render(Map.of());
        String postMsg = finalAnswerPostTemplate.render("task", task);

        LLMRequest request = LLMRequest.builder()
                .addMessage("system", preMsg)
                .addMessage("user", postMsg)
                .maxTokens(512)
                .build();

        try {
            return llm.complete(request).content();
        } catch (Exception e) {
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
