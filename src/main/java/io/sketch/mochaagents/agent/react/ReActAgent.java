package io.sketch.mochaagents.agent.react;

import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.AgentEvents;
import java.util.function.Predicate;
import io.sketch.mochaagents.agent.impl.BaseAgent;
import io.sketch.mochaagents.agent.MemoryProvider;
import io.sketch.mochaagents.agent.SystemPromptProvider;
import io.sketch.mochaagents.agent.react.StepResult;
import io.sketch.mochaagents.agent.react.Termination;
import io.sketch.mochaagents.agent.react.strategy.ReActLoop;
import io.sketch.mochaagents.context.ContextCompressor;
import io.sketch.mochaagents.context.ContextManager;
import io.sketch.mochaagents.context.LLMContextCompressor;
import io.sketch.mochaagents.evaluation.EvaluationResult;
import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import io.sketch.mochaagents.memory.AgentMemory;
import io.sketch.mochaagents.agent.react.step.*;
import io.sketch.mochaagents.perception.LayeredContextBuilder;
import io.sketch.mochaagents.perception.PerceptionObserver;
import io.sketch.mochaagents.perception.PerceptionResult;
import io.sketch.mochaagents.plan.Plan;
import io.sketch.mochaagents.plan.PlanStep;
import io.sketch.mochaagents.plan.PlanningRequest;
import io.sketch.mochaagents.plan.ExecutionFeedback;
import io.sketch.mochaagents.prompt.PromptTemplate;
import io.sketch.mochaagents.reasoning.ReasoningChain;
import io.sketch.mochaagents.reasoning.ReasoningStep;
import io.sketch.mochaagents.reasoning.RecoveryStateMachine;
import io.sketch.mochaagents.reasoning.ThinkingConfig;
import io.sketch.mochaagents.reasoning.EffortLevel;
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * ReActAgent — ReAct (Reasoning + Acting) loop with deep capability integration.
 *
 * <p>Perception, reasoning, and planning are woven into <em>every step</em> of the loop,
 * not just called once before/after. This creates a continuous feedback cycle:
 *
 * <pre>
 *   PERCEIVE → REASON → PLAN → ACT → OBSERVE → (loop)
 *       ↑                                      ↓
 *       └──────────── feedback ────────────────┘
 * </pre>
 *
 * <h2>Quick Start</h2>
 * <pre>{@code
 * var agent = ToolCallingAgent.builder()
 *     .name("my-agent").llm(llm).tools(tools)
 *     .reasoner(new DefaultReasoner(llm))       // per-step reasoning
 *     .planner(new DynamicPlanner<>(strategy))   // per-step plan tracking
 *     .perceptor(new CodebasePerceptor())        // continuous perception
 *     .maxSteps(10).build();
 * String answer = agent.run("Refactor the auth module");
 * }</pre>
 *
 * @see ToolCallingAgent
 * @see CodeAgent
 * @author lanxia39@163.com
 */
public abstract class ReActAgent extends BaseAgent<String, String>
        implements MemoryProvider, SystemPromptProvider {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    // ============ ReAct core components ============

    protected final LLM llm;
    protected final io.sketch.mochaagents.llm.router.LLMRouter router;
    protected final io.sketch.mochaagents.llm.OptimizationConfig optimization;
    protected final io.sketch.mochaagents.llm.CostTracker costTracker;
    protected final AgentMemory memory = new AgentMemory();
    protected final int maxSteps;
    protected final int planningInterval;
    protected final boolean addBaseTools;
    protected final Map<String, ReActAgent> managedAgents = new LinkedHashMap<>();
    protected final io.sketch.mochaagents.orchestration.Orchestrator orchestrator;
    protected final io.sketch.mochaagents.agent.AgentEvents events = new io.sketch.mochaagents.agent.AgentEvents();
    protected final io.sketch.mochaagents.agent.react.Hooks hooks = new io.sketch.mochaagents.agent.react.Hooks();
    protected final io.sketch.mochaagents.tool.ToolExecutor toolExecutor;

    /** Pluggable execution paradigm — defaults to ReActLoop. */
    protected final AgenticLoop<String, String> agenticLoop;

    public Runnable onEvent(io.sketch.mochaagents.agent.AgentEvents.Listener l) { return events.subscribe(l); }
    public Hooks hooks() { return hooks; }

    /** Switch execution paradigm at runtime. */
    public ReActAgent withAgenticLoop(AgenticLoop<String, String> loop) {
        return new AgenticLoopSwitcher(this, loop);
    }

    /**
     * Unified tool execution — permission check → pre-hooks → execute → post-hooks.
     * Replaces the old direct tool.call() path in ToolCallingAgent/CodeAgent.
     */
    protected io.sketch.mochaagents.tool.ToolResult executeTool(String toolName,
                                                                  Map<String, Object> arguments) {
        return toolExecutor.execute(toolName, arguments);
    }

    /** Resolve the effective loop: configured loop, or default ReActLoop. */
    private AgenticLoop<String, String> resolveLoop() {
        if (agenticLoop != null) return agenticLoop;
        // Default: classic ReAct loop
        return new ReActLoop<>(
                this::planStep,
                this::executeIntegratedStep,
                planningInterval);
    }

    // ============ Runtime capability state (updated per-step) ============

    private Plan<String> activePlan;
    private int planStepIndex;
    private int planDeviations;
    private ReasoningChain activeReasoning;
    private final List<String> perceptionHistory = new ArrayList<>();
    private static final int MAX_PLAN_DEVIATIONS = 3;

    // ============ Context ============

    private io.sketch.mochaagents.context.AutoCompactor autoCompactor;

    protected ContextManager newContextManager() {
        ContextCompressor compressor = new LLMContextCompressor(llm);
        ContextManager cm = new ContextManager(8192, (chunks, maxT) -> chunks, compressor);
        if (autoCompactor == null) autoCompactor = new io.sketch.mochaagents.context.AutoCompactor(cm, 8192);
        return cm;
    }

    /** Public — allows REPL / users to trigger context compaction manually. */
    public void autoCompact() {
        if (autoCompactor != null) autoCompactor.checkAndCompact();
    }

    // ============ Prompt templates ============

    protected PromptTemplate systemPromptTemplate;
    protected PromptTemplate planningPromptTemplate;
    protected PromptTemplate finalAnswerPreTemplate;
    protected PromptTemplate finalAnswerPostTemplate;

    protected ReActAgent(Builder<?> builder) {
        super(builder);
        this.optimization = builder.optimization;
        this.costTracker = new io.sketch.mochaagents.llm.CostTracker();

        LLM rawLlm = builder.llm;
        if (rawLlm != null && optimization.cacheMaxEntries() > 0) {
            rawLlm = new io.sketch.mochaagents.llm.CachingLLM(rawLlm, costTracker, optimization.cacheMaxEntries());
        }
        this.llm = rawLlm;
        this.router = builder.router;
        this.orchestrator = builder.orchestrator;
        this.maxSteps = builder.maxSteps;
        this.planningInterval = builder.planningInterval;
        this.addBaseTools = builder.addBaseTools;
        this.systemPromptTemplate = builder.systemPromptTemplate != null
                ? builder.systemPromptTemplate : PromptTemplate.of("");
        this.planningPromptTemplate = builder.planningPromptTemplate;
        this.finalAnswerPreTemplate = builder.finalAnswerPreTemplate;
        this.finalAnswerPostTemplate = builder.finalAnswerPostTemplate;
        this.agenticLoop = builder.agenticLoop;

        // Wire ToolExecutor with hooks + permissions — unified tool execution pipeline
        this.toolExecutor = new io.sketch.mochaagents.tool.ToolExecutor(
                toolRegistry, 60_000, 2, 500);
        this.toolExecutor.withHooks(hooks)
                .withEvents(events);  // real-time diff display
        if (builder.permissionRules != null) {
            this.toolExecutor.withPermissions(builder.permissionRules);
        }

        setupManagedAgents(builder.managedAgents);
        setupTools(builder.tools);
    }

    protected LLM resolveLlm(LLMRequest request) {
        if (router != null && !router.getProviders().isEmpty()) {
            return router.route(request);
        }
        return llm;
    }

    // ============ Public API ============

    public AgentMemory memory() { return memory; }

    public String buildSystemPrompt() {
        String base = systemPromptTemplate.render(Map.of(
                "tools", formatTools(),
                "managed_agents", formatManagedAgents(),
                "instructions", description != null ? description : ""
        ));
        // Use LayeredContextBuilder to add system context (git, platform)
        return contextBuilder.buildFullContext(base, "");
    }

    // ============ Execution entry points ============

    public String run(String task) { return run(AgentContext.of(task), maxSteps); }

    public io.sketch.mochaagents.agent.ExecutionReport runAndReport(String task) {
        return runAndReport(AgentContext.of(task));
    }

    public io.sketch.mochaagents.agent.ExecutionReport runAndReport(AgentContext ctx) {
        long start = System.currentTimeMillis();
        List<String> errors = new ArrayList<>();
        String result;
        try {
            result = run(ctx);
        } catch (Exception e) {
            result = "Error: " + e.getMessage();
            errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        long elapsed = System.currentTimeMillis() - start;

        int steps = memory.steps().size();
        String summary = String.format("[%s] %d steps, %dms, $%.4f, %d in + %d out tokens",
                name, steps, elapsed,
                costTracker.estimatedTotalCost(),
                costTracker.totalInputTokens(),
                costTracker.totalOutputTokens());

        return new io.sketch.mochaagents.agent.ExecutionReport(
                result, steps, elapsed,
                costTracker.estimatedTotalCost(),
                costTracker.totalInputTokens(),
                costTracker.totalOutputTokens(),
                errors, summary);
    }

    public io.sketch.mochaagents.llm.CostTracker costTracker() { return costTracker; }

    public String run(String task, int maxSteps) {
        return run(AgentContext.of(task), maxSteps);
    }

    public String runStreaming(AgentContext ctx, java.util.function.Consumer<String> onToken) {
        return runStreaming(ctx, maxSteps, onToken);
    }

    public String runStreaming(AgentContext ctx, int maxSteps, java.util.function.Consumer<String> onToken) {
        long startMs = System.currentTimeMillis();
        String task = ctx.userMessage();
        log.info("Agent '{}' starting (streaming), maxSteps={}, task={}", name, maxSteps, truncate(task, 120));
        onToken.accept("[Agent:" + name + "] ");

        String systemPrompt = buildSystemPrompt();
        systemPrompt = enrichFromContext(systemPrompt, ctx);
        memory.reset(systemPrompt);
        memory.appendTask(task);
        injectConversationHistory(ctx);

        ContextManager ctxMgr = newContextManager();

        // Pre-loop: initialize capabilities
        initializeCapabilities(task, ctxMgr);

        // Streaming ReAct loop with integrated steps
        ReActLoop<String, String> loop = new ReActLoop<>(
                (ReActLoop.PlanningFn<String>) this::planStep,
                (ReActLoop.StepExecutor<String>) (step, input, mem) ->
                        executeReActStepStreaming(step, input, mem, onToken),
                planningInterval);

        Predicate<StepResult> condition = Termination.maxSteps(maxSteps)
                .or(Termination.onError());
        String result = loop.run(this, task, condition);

        if (!memory.hasFinalAnswer()) {
            result = provideFinalAnswer(task);
            memory.appendFinalAnswer(result);
        }

        EvaluationResult eval = evaluate(task, result, ctxMgr);
        reflectAndLearn(task, result, eval, ctxMgr);
        ctxMgr.compress();

        long elapsed = System.currentTimeMillis() - startMs;
        onToken.accept("\n[" + name + " done in " + elapsed + "ms, " + memory.steps().size() + " steps]");
        log.info("Agent '{}' streaming completed in {}ms", name, elapsed);
        return result;
    }

    protected StepResult executeReActStepStreaming(
            int stepNumber, String input, AgentMemory memory,
            java.util.function.Consumer<String> onToken) {
        return executeReActStep(stepNumber, input, memory);
    }

    public String run(AgentContext ctx) {
        return run(ctx, maxSteps);
    }

    /** Main entry: ReAct loop with deep capability integration woven into every step. */
    public String run(AgentContext ctx, int maxSteps) {
        long startMs = System.currentTimeMillis();
        String task = ctx.userMessage();
        log.info("Agent '{}' starting, session={}, user={}, maxSteps={}, task={}",
                name, ctx.sessionId(), ctx.userId(), maxSteps, truncate(task, 120));
        events.fire(new AgentEvents.Event(AgentEvents.STARTED, name, task, 0));

        String systemPrompt = buildSystemPrompt();
        systemPrompt = enrichFromContext(systemPrompt, ctx);

        memory.reset(systemPrompt);
        memory.appendTask(task);
        injectConversationHistory(ctx);

        // ===== Pre-loop: initialize capabilities (perceive, reason, plan) =====
        ContextManager ctxMgr = newContextManager();
        initializeCapabilities(task, ctxMgr);

        // ===== ReAct loop with integrated capability hooks per step =====
        AgenticLoop<String, String> loop = resolveLoop();
        Predicate<StepResult> condition = Termination.maxSteps(maxSteps)
                .or(Termination.onError());

        String result = loop.run(this, task, condition);

        if (!memory.hasFinalAnswer()) {
            log.warn("Agent '{}' exceeded max steps, providing fallback answer", name);
            result = provideFinalAnswer(task);
            memory.appendFinalAnswer(result);
        }

        // ===== Post-loop: final evaluation, memory storage, and learning =====
        autoCompact();
        EvaluationResult eval = evaluate(task, result, ctxMgr);
        storeMemories(task, result);
        reflectAndLearn(task, result, eval, ctxMgr);
        ctxMgr.compress();

        long elapsed = System.currentTimeMillis() - startMs;
        log.info("Agent '{}' completed in {}ms, steps={}, planDeviations={}, result={}",
                name, elapsed, memory.steps().size(), planDeviations, truncate(result, 300));

        events.fire(new AgentEvents.Event(AgentEvents.COMPLETED, name, result, elapsed));
        events.fire(new AgentEvents.Event(AgentEvents.COST, name,
                new double[]{costTracker.estimatedTotalCost(),
                        (double) costTracker.totalInputTokens(),
                        (double) costTracker.totalOutputTokens()}, elapsed));
        return result;
    }

    // ============ Integrated step execution ============

    /**
     * Execute one ReAct step with perception/reasoning/planning woven in.
     *
     * <p>Each step follows the integrated cycle:
     * <ol>
     *   <li><b>Pre-reason</b>: inject current reasoning/plan state into messages</li>
     *   <li><b>Act</b>: LLM call + tool execution (delegated to subclass)</li>
     *   <li><b>Perceive</b>: observe what changed after the action</li>
     *   <li><b>Track plan</b>: check if action matches expected plan step</li>
     *   <li><b>Adapt</b>: replan/re-reason if deviation detected</li>
     * </ol>
     */
    private StepResult executeIntegratedStep(int stepNumber, String input, AgentMemory memory) {
        // 1. Pre-step: inject capability context into system prompt
        injectCapabilityContext(stepNumber, memory);

        // 2. Act: delegate to subclass (ToolCallingAgent / CodeAgent)
        StepResult result = executeReActStep(stepNumber, input, memory);

        // 3. Perceive: continuous environmental awareness after action
        perceiveAfterAction(result);

        // 4. Track plan: compare action to expected plan step
        trackPlanProgress(stepNumber, result);

        // 5. Adapt: trigger replanning if deviation threshold exceeded
        if (planDeviations >= MAX_PLAN_DEVIATIONS) {
            replanFromDeviation(input, result);
            planDeviations = 0;
        }

        return result;
    }

    // ============ Capability initialization (pre-loop) ============

    /** Initialize perception, reasoning, and planning before the loop starts. */
    private void initializeCapabilities(String task, ContextManager ctx) {
        // 1. Memory injection from past sessions
        injectMemories(task, ctx);

        // 2. Perception: initial environmental snapshot
        perceiveAndRemember(task, ctx);

        // 3. Reasoning: analyze the task
        ReasoningChain chain = reason(task, ctx);
        this.activeReasoning = chain;

        // 4. Planning: generate execution blueprint
        planAndRemember(task, chain, ctx);
    }

    // ============ Per-step capability hooks ============

    /**
     * Inject current reasoning state and plan progress into AgentMemory
     * so the LLM sees them as part of the conversation context.
     */
    private void injectCapabilityContext(int stepNumber, AgentMemory memory) {
        StringBuilder ctx = new StringBuilder();

        // Reasoning context: where are we in the reasoning chain?
        if (activeReasoning != null && !activeReasoning.steps().isEmpty()) {
            ctx.append("[Reasoning State]\n");
            int totalSteps = activeReasoning.steps().size();
            int currentIdx = Math.min(stepNumber - 1, totalSteps - 1);
            ReasoningStep current = activeReasoning.steps().get(currentIdx);
            ctx.append("Step ").append(current.index()).append("/").append(totalSteps)
                    .append(": ").append(current.thought()).append("\n");
            ctx.append("Confidence: ").append(String.format("%.2f", current.confidence())).append("\n");
        }

        // Plan progress: which step are we on?
        if (activePlan != null && !activePlan.getSteps().isEmpty()) {
            ctx.append("\n[Plan Progress]\n");
            ctx.append(planStepIndex).append("/").append(activePlan.getSteps().size())
                    .append(" steps completed\n");
            if (planDeviations > 0) {
                ctx.append("Deviations: ").append(planDeviations)
                        .append(" (replanning at ").append(MAX_PLAN_DEVIATIONS).append(")\n");
            }

            // Show current and upcoming plan steps
            List<PlanStep> steps = activePlan.getSteps();
            for (int i = planStepIndex; i < Math.min(planStepIndex + 3, steps.size()); i++) {
                String marker = i == planStepIndex ? "→ " : "  ";
                ctx.append(marker).append("Step ").append(i + 1).append(": ")
                        .append(steps.get(i).description()).append("\n");
            }
        }

        // Recent perception updates (last 3)
        if (!perceptionHistory.isEmpty()) {
            ctx.append("\n[Recent Perceptions]\n");
            int start = Math.max(0, perceptionHistory.size() - 3);
            for (int i = start; i < perceptionHistory.size(); i++) {
                ctx.append("- ").append(perceptionHistory.get(i)).append("\n");
            }
        }

        if (!ctx.isEmpty()) {
            memory.append(ContentStep.systemPrompt(ctx.toString()));
        }
    }

    /** Perceive the environment after an action — what changed? */
    private void perceiveAfterAction(StepResult result) {
        if (perceptor == null) return;

        String observation = result.observation();
        if (observation == null || observation.isEmpty()) return;

        try {
            // Use PerceptionObserver if available for continuous tracking
            if (perceptionObserver != null) {
                PerceptionResult<String> pr = perceptionObserver.observeAction(observation);
                String data = pr.data() != null ? pr.data() : "";
                if (!data.isEmpty()) {
                    perceptionHistory.add(truncate(data, 200));
                    memory.append(ContentStep.systemPrompt("[Perception Update]:\n" + data
                            + "\n" + perceptionObserver.buildEnrichedContext()));
                }
            } else {
                PerceptionResult<String> pr = perceptor.perceive(observation);
                String data = pr.data() != null ? pr.data() : "";
                if (!data.isEmpty()) {
                    perceptionHistory.add(truncate(data, 200));
                    memory.append(ContentStep.systemPrompt("[Perception Update]:\n" + data));
                }
            }
            log.debug("Agent '{}' perception update: {}", name, truncate(observation, 100));
        } catch (Exception e) {
            log.debug("Agent '{}' perception step failed: {}", name, e.getMessage());
        }
    }

    /** Track plan progress: compare executed action against expected plan step. */
    private void trackPlanProgress(int stepNumber, StepResult result) {
        if (activePlan == null || activePlan.getSteps().isEmpty()) return;

        List<PlanStep> steps = activePlan.getSteps();
        if (planStepIndex >= steps.size()) return;

        PlanStep expected = steps.get(planStepIndex);
        String action = result.action();
        String observation = result.observation();

        // Heuristic match: does the action relate to the expected step?
        boolean matches = actionMatchesPlanStep(action, observation, expected);

        if (matches) {
            // Progress: mark current step complete, advance
            expected.markSuccess(io.sketch.mochaagents.plan.ExecutionResult.success(observation));
            planStepIndex++;
            log.debug("Agent '{}' plan step {}/{} completed: {}",
                    name, planStepIndex, steps.size(), truncate(expected.description(), 80));
        } else if (result.state() == io.sketch.mochaagents.agent.react.LoopState.ERROR) {
            expected.markFailed(io.sketch.mochaagents.plan.ExecutionResult.failure(result.error()));
            planDeviations++;
            log.debug("Agent '{}' plan step {} failed: {}", name, planStepIndex, result.error());
        } else {
            // Action doesn't match plan but isn't an error — minor deviation
            planDeviations++;
            log.debug("Agent '{}' plan deviation #{}: expected '{}', got '{}'",
                    name, planDeviations, truncate(expected.description(), 60), action);
        }
    }

    /** Simple heuristic: does the action semantically match the plan step? */
    private boolean actionMatchesPlanStep(String action, String observation, PlanStep step) {
        if (action == null) return false;
        String desc = step.description().toLowerCase();
        String act = action.toLowerCase();

        // Direct keyword overlap
        for (String word : desc.split("\\s+")) {
            if (word.length() > 3 && act.contains(word)) return true;
        }

        // Observation contains expected output keywords from plan description
        if (observation != null) {
            String obs = observation.toLowerCase();
            for (String word : desc.split("\\s+")) {
                if (word.length() > 3 && obs.contains(word)) return true;
            }
        }

        // Managed agent delegation matches agentId
        if (step.agentId() != null && !step.agentId().isEmpty()
                && act.contains(step.agentId().toLowerCase())) return true;

        return false;
    }

    /** Trigger replanning when too many deviations occur. */
    private void replanFromDeviation(String input, StepResult result) {
        if (planner == null || activePlan == null) return;

        log.info("Agent '{}' triggering replan after {} deviations", name, MAX_PLAN_DEVIATIONS);

        ExecutionFeedback feedback = new ExecutionFeedback(
                "step-" + planStepIndex,
                io.sketch.mochaagents.plan.ExecutionFeedback.ExecutionStatus.FAILED,
                null, result.error(), Map.of(), 0);

        Plan<String> newPlan = planner.replan(activePlan, feedback);
        if (newPlan != null && !newPlan.getSteps().isEmpty()) {
            this.activePlan = newPlan;
            this.planStepIndex = 0;
            this.planDeviations = 0;

            // Re-reason from current state
            if (reasoner != null) {
                this.activeReasoning = reasoner.reason(
                        input + "\n(Replanning after deviations. New plan: "
                                + newPlan.getSteps().size() + " steps)");
            }

            memory.appendPlanning(newPlan.serialize(), "[Replanned after deviations]", 0, 0);
            log.info("Agent '{}' replan complete: {} new steps", name, newPlan.getSteps().size());
        }
    }

    // ============ BaseAgent overrides ============

    @Override
    protected String doExecute(String input, AgentContext actx) {
        return run(actx);
    }

    @Override
    protected String doExecute(String input, ContextManager ctx) {
        return run(AgentContext.of(input));
    }

    /**
     * Execute a single ReAct step. Subclasses (ToolCallingAgent / CodeAgent)
     * implement the actual LLM call and tool/code execution.
     */
    protected abstract StepResult executeReActStep(
            int stepNumber, String input, AgentMemory memory);

    // ============ Internal methods ============

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
                .thinkingConfig(thinkingConfig)
                .effort(effortLevel)
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

    /** Auto-extract and persist memories after task completion. */
    private void storeMemories(String task, String result) {
        if (memoryManager == null) return;
        try {
            List<io.sketch.mochaagents.memory.Memory> snapshots = memory.snapshot();
            for (var mem : snapshots) {
                memoryManager.store(mem);
            }
            if (!snapshots.isEmpty()) {
                log.debug("Agent '{}' stored {} memory entries", name, snapshots.size());
            }
        } catch (Exception e) {
            log.debug("Agent '{}' memory storage failed: {}", name, e.getMessage());
        }
    }

    /** Initial perception + memory injection (called once before loop). */
    private void perceiveAndRemember(String input, ContextManager ctx) {
        if (perceptor == null) return;
        PerceptionResult<String> result = perceptor.perceive(input);
        String data = result.data() != null ? result.data() : "";
        if (!data.isEmpty()) {
            memory.append(ContentStep.systemPrompt("[Initial Perception]:\n" + data));
            perceptionHistory.add(truncate(data, 200));
        }
        ctx.addChunk(newChunk("perception", data));
    }

    /** Initial plan generation (called once before loop). */
    private void planAndRemember(String input, ReasoningChain chain, ContextManager ctx) {
        if (planner == null) return;
        @SuppressWarnings("unchecked")
        Plan<String> plan = (Plan<String>) planner.generatePlan(
                PlanningRequest.<String>builder()
                        .goal(input)
                        .context(chain != null ? chain.summarize() : "")
                        .build());
        if (plan != null && !plan.getSteps().isEmpty()) {
            this.activePlan = plan;
            this.planStepIndex = 0;
            this.planDeviations = 0;
            memory.appendPlanning(plan.serialize(), "[Initial plan: " + plan.getSteps().size() + " steps]", 0, 0);
            ctx.addChunk(newChunk("plan", plan.serialize()));
            log.info("Agent '{}' initial plan: {} steps", name, plan.getSteps().size());
        }
    }

    private void injectConversationHistory(AgentContext ctx) {
        String history = ctx.conversationHistory();
        if (history == null || history.isEmpty()) return;

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
        // Inject user context from LayeredContextBuilder (CLAUDE.md, date)
        sb.append("\n").append(contextBuilder.buildUserContext());
        return sb.toString();
    }

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
                .thinkingConfig(thinkingConfig)
                .effort(effortLevel)
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

    /** Convert memory to LLM messages (used by subclasses). */
    protected List<Map<String, String>> writeMemoryToMessages() {
        List<Map<String, String>> messages = new ArrayList<>();

        if (memory.systemPrompt() != null && !memory.systemPrompt().isEmpty()) {
            messages.add(Map.of("role", "system", "content", memory.systemPrompt()));
        }

        int stepCount = memory.steps().size();
        for (MemoryStep step : memory.steps()) {
            if (step instanceof ContentStep cs && cs.isSystemPrompt()) {
                messages.add(Map.of("role", "system", "content", cs.text()));
            } else if (step instanceof ContentStep cs && cs.isTask()) {
                messages.add(Map.of("role", "user", "content", cs.text()));
            } else if (step instanceof PlanningStep ps) {
                messages.add(Map.of("role", "assistant", "content", "Plan:\n" + ps.plan()));
            } else if (step instanceof ActionStep as) {
                if (as.modelOutput() != null && !as.modelOutput().isEmpty()) {
                    messages.add(Map.of("role", "assistant", "content", as.modelOutput()));
                }
                if (as.observation() != null && !as.observation().isEmpty()) {
                    messages.add(Map.of("role", "user",
                            "content", "Observation:\n" + as.observation()));
                }
            }
        }

        log.debug("Agent '{}' built {} LLM messages from {} steps", name, messages.size(), stepCount);
        return messages;
    }

    // ============ Init helpers ============

    private void setupManagedAgents(List<ReActAgent> agents) {
        if (agents == null) return;
        for (ReActAgent a : agents) {
            managedAgents.put(a.name, a);
            if (orchestrator != null) {
                orchestrator.register(a, io.sketch.mochaagents.orchestration.Role.worker(a.name));
            }
        }
    }

    private void setupTools(List<Tool> tools) {
        if (toolRegistry == null) return;
        if (tools != null) {
            tools.forEach(toolRegistry::register);
        }
        if (!toolRegistry.has("final_answer")) {
            toolRegistry.register(new FinalAnswerTool());
        }
        // Register managed agents as callable tools
        for (var entry : managedAgents.entrySet()) {
            String agentName = entry.getKey();
            if (!toolRegistry.has("delegate_" + agentName)) {
                toolRegistry.register(new ManagedAgentTool(agentName, entry.getValue().description));
            }
        }
    }

    // ============ Formatting ============

    protected String formatTools() {
        if (toolRegistry == null) return "None";
        StringBuilder sb = new StringBuilder();
        for (Tool t : toolRegistry.all()) {
            if (t instanceof ManagedAgentTool) continue; // shown separately
            sb.append("- ").append(t.getName()).append(": ").append(t.getDescription()).append("\n");
        }
        return sb.toString();
    }

    protected String formatManagedAgents() {
        if (managedAgents.isEmpty()) return "None";
        StringBuilder sb = new StringBuilder();
        for (var entry : managedAgents.entrySet()) {
            sb.append("- delegate_").append(entry.getKey())
                    .append(": ").append(entry.getValue().description).append("\n");
        }
        return sb.toString();
    }

    /**
     * Delegate a task to a managed agent via the orchestrator.
     * Called when the LLM selects a managed agent as a tool.
     */
    public String delegateToManagedAgent(String agentName, String task) {
        ReActAgent sub = managedAgents.get(agentName);
        if (sub == null) {
            if (orchestrator != null && orchestrator.getTeam().getRole(agentName).isPresent()) {
                log.info("Delegating '{}' via orchestrator to {}", task, agentName);
                try {
                    Object result = orchestrator.orchestrate(task,
                            io.sketch.mochaagents.orchestration.OrchestrationStrategy.sequential());
                    return result != null ? result.toString() : "No result from orchestrator";
                } catch (Exception e) {
                    log.error("Orchestrator delegation failed: {}", e.getMessage());
                    return "Orchestrator error: " + e.getMessage();
                }
            }
            return "Managed agent not found: " + agentName;
        }
        log.info("Delegating '{}' to managed agent '{}'", task, agentName);
        return sub.run(AgentContext.of(task));
    }

    // ============ Inner tools ============

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

    /** Tool wrapper that exposes a managed agent as a callable tool. */
    final class ManagedAgentTool implements Tool {
        private final String agentName;
        private final String agentDesc;

        ManagedAgentTool(String agentName, String agentDesc) {
            this.agentName = agentName;
            this.agentDesc = agentDesc;
        }

        @Override public String getName() { return "delegate_" + agentName; }
        @Override public String getDescription() {
            return "Delegate a task to the '" + agentName + "' agent. " + agentDesc;
        }
        @Override public Map<String, ToolInput> getInputs() {
            return Map.of("task", ToolInput.any("The task to delegate to " + agentName));
        }
        @Override public String getOutputType() { return "any"; }
        @Override public Object call(Map<String, Object> arguments) {
            String task = String.valueOf(arguments.getOrDefault("task", ""));
            return delegateToManagedAgent(agentName, task);
        }
        @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.MEDIUM; }
    }

    // ============ Utilities ============

    protected static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ============ Builder ============

    @SuppressWarnings("unchecked")
    public abstract static class Builder<T extends Builder<T>>
            extends BaseAgent.Builder<String, String, T> {

        protected LLM llm;
        protected io.sketch.mochaagents.llm.router.LLMRouter router;
        protected io.sketch.mochaagents.orchestration.Orchestrator orchestrator;
        protected List<Tool> tools = new ArrayList<>();
        protected List<ReActAgent> managedAgents = new ArrayList<>();
        protected int maxSteps = 20;
        protected int planningInterval;
        protected boolean addBaseTools;
        protected PromptTemplate systemPromptTemplate;
        protected PromptTemplate planningPromptTemplate;
        protected PromptTemplate finalAnswerPreTemplate;
        protected PromptTemplate finalAnswerPostTemplate;
        protected io.sketch.mochaagents.llm.OptimizationConfig optimization
                = io.sketch.mochaagents.llm.OptimizationConfig.balanced();
        protected AgenticLoop<String, String> agenticLoop;
        protected io.sketch.mochaagents.interaction.permission.PermissionRules permissionRules;

        public T llm(LLM llm) { this.llm = llm; return (T) this; }
        public T optimization(io.sketch.mochaagents.llm.OptimizationConfig cfg) { this.optimization = cfg; return (T) this; }
        public T router(io.sketch.mochaagents.llm.router.LLMRouter router) { this.router = router; return (T) this; }
        public T orchestrator(io.sketch.mochaagents.orchestration.Orchestrator o) { this.orchestrator = o; return (T) this; }
        public T tools(List<Tool> tools) { this.tools = tools; return (T) this; }
        public T managedAgents(List<ReActAgent> agents) { this.managedAgents = agents; return (T) this; }
        public T maxSteps(int maxSteps) { this.maxSteps = maxSteps; return (T) this; }
        public T planningInterval(int interval) { this.planningInterval = interval; return (T) this; }
        public T addBaseTools(boolean add) { this.addBaseTools = add; return (T) this; }
        public T systemPromptTemplate(PromptTemplate t) { this.systemPromptTemplate = t; return (T) this; }
        public T planningPromptTemplate(PromptTemplate t) { this.planningPromptTemplate = t; return (T) this; }
        public T finalAnswerPreTemplate(PromptTemplate t) { this.finalAnswerPreTemplate = t; return (T) this; }
        public T finalAnswerPostTemplate(PromptTemplate t) { this.finalAnswerPostTemplate = t; return (T) this; }

        /** Set a custom execution paradigm (ReAct, Reflexion, ReWOO, TAO, OPAR). */
        public T agenticLoop(AgenticLoop<String, String> loop) {
            this.agenticLoop = loop; return (T) this;
        }
        public T permissionRules(io.sketch.mochaagents.interaction.permission.PermissionRules rules) {
            this.permissionRules = rules; return (T) this;
        }
    }

    // ============ AgenticLoopSwitcher ============

    /**
     * Wrapper that delegates to another agent but overrides the execution loop.
     * Allows runtime paradigm switching without rebuilding.
     */
    private static final class AgenticLoopSwitcher extends ReActAgent {
        private final ReActAgent delegate;
        private final AgenticLoop<String, String> loop;

        AgenticLoopSwitcher(ReActAgent delegate, AgenticLoop<String, String> loop) {
            super(createBuilder(delegate, loop));
            this.delegate = delegate;
            this.loop = loop;
        }

        private static Builder<?> createBuilder(ReActAgent delegate, AgenticLoop<String, String> loop) {
            io.sketch.mochaagents.agent.impl.ToolCallingAgent.Builder b
                    = io.sketch.mochaagents.agent.impl.ToolCallingAgent.builder();
            b.name(delegate.name);
            b.description(delegate.description);
            b.llm(delegate.llm);
            b.maxSteps(delegate.maxSteps);
            b.agenticLoop(loop);
            return b;
        }

        @Override protected StepResult executeReActStep(int step, String input, AgentMemory mem) {
            return delegate.executeReActStep(step, input, mem);
        }
        @Override public String buildSystemPrompt() { return delegate.buildSystemPrompt(); }
    }
}
