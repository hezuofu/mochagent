package io.sketch.mochaagents.agent.loop;

import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.AgentLoop;
import io.sketch.mochaagents.agent.event.AgentEvents;
import java.util.function.Predicate;
import io.sketch.mochaagents.agent.internal.BaseAgent;
import io.sketch.mochaagents.memory.MemoryProvider;
import io.sketch.mochaagents.prompt.SystemPromptProvider;
import io.sketch.mochaagents.agent.loop.StepResult;
import io.sketch.mochaagents.agent.loop.strategy.ReActLoop;
import io.sketch.mochaagents.tool.Hooks;
import io.sketch.mochaagents.context.ContextCompressor;
import io.sketch.mochaagents.context.Context;
import io.sketch.mochaagents.context.LLMContextCompressor;
import io.sketch.mochaagents.evaluation.EvaluationResult;
import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import io.sketch.mochaagents.memory.MemoryManager;
import io.sketch.mochaagents.memory.MemoryRecord;
import io.sketch.mochaagents.agent.loop.step.*;
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
 * @author lanxia39@163.com
 */
public abstract class ReActAgent extends BaseAgent<String, String>
        implements MemoryProvider, SystemPromptProvider {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    // ── Core components ──

    protected final LLM llm;
    protected final io.sketch.mochaagents.llm.LLMRouter router;
    protected final io.sketch.mochaagents.llm.OptimizationConfig optimization;
    protected final io.sketch.mochaagents.llm.CostTracker costTracker;
    protected final MemoryManager memory = MemoryManager.create();
    protected final int maxSteps;
    protected final int planningInterval;
    protected final boolean addBaseTools;
    protected final Map<String, ReActAgent> managedAgents = new LinkedHashMap<>();
    protected final io.sketch.mochaagents.orchestration.Orchestrator orchestrator;
    protected final io.sketch.mochaagents.agent.event.AgentEvents events = new io.sketch.mochaagents.agent.event.AgentEvents();
    protected final io.sketch.mochaagents.tool.Hooks hooks = new io.sketch.mochaagents.tool.Hooks();
    protected final io.sketch.mochaagents.tool.ToolExecutor toolExecutor;

    /** Pluggable execution paradigm — defaults to ReActLoop. */
    protected final AgentLoop<String, String> agentLoop;

    // ── Self-learning (GenericAgent pattern, via AgentMemory) ──

    private int turnCount;

    // ── Cognitive capabilities (moved from BaseAgent — live here, not in base) ──

    protected final io.sketch.mochaagents.perception.Perceptor<String, String> perceptor;
    protected final io.sketch.mochaagents.reasoning.Reasoner reasoner;
    protected final io.sketch.mochaagents.plan.Planner<String> planner;
    protected final io.sketch.mochaagents.evaluation.Evaluator evaluator;
    protected final io.sketch.mochaagents.perception.LayeredContextBuilder contextBuilder;
    protected final io.sketch.mochaagents.perception.PerceptionObserver perceptionObserver;

    public Runnable onEvent(io.sketch.mochaagents.agent.event.AgentEvents.Listener l) { return events.subscribe(l); }
    public Hooks hooks() { return hooks; }

    /** Switch execution paradigm at runtime. */
    public ReActAgent withAgentLoop(AgentLoop<String, String> loop) {
        return new AgentLoopSwitcher(this, loop);
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
    private AgentLoop<String, String> resolveLoop() {
        if (agentLoop != null) return agentLoop;
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
        this.agentLoop = builder.agentLoop;
        this.perceptor = builder.perceptor;
        this.reasoner = builder.reasoner;
        this.planner = builder.planner;
        this.evaluator = builder.evaluator;
        this.contextBuilder = new io.sketch.mochaagents.perception.LayeredContextBuilder(
                Path.of(System.getProperty("user.dir", ".")));
        this.perceptionObserver = perceptor != null
                ? new PerceptionObserver(contextBuilder, perceptor) : null;

        // Wire ToolExecutor with hooks + permissions — unified tool execution pipeline
        this.toolExecutor = new io.sketch.mochaagents.tool.ToolExecutor(
                toolRegistry, 60_000, 2, 500);
        this.toolExecutor.withHooks(hooks)
                .withEvents(events);
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

    public MemoryManager memory() { return memory; }

    public String buildSystemPrompt() {
        String base = systemPromptTemplate.render(Map.of(
                "tools", formatTools(),
                "managed_agents", formatManagedAgents(),
                "instructions", description != null ? description : ""
        ));
        // Use LayeredContextBuilder to add system context (git, platform)
        String full = contextBuilder.buildFullContext(base, "");
        // Inject working memory + global memory (GenericAgent pattern)
        full += memory.workingContext();
        full += memory.globalContext();
        return full;
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

        

        // Pre-loop: initialize capabilities
        initializeCapabilities(task, ctx);

        // Streaming ReAct loop with integrated steps
        ReActLoop<String, String> loop = new ReActLoop<>(
                (ReActLoop.PlanningFn<String>) this::planStep,
                (ReActLoop.StepExecutor<String>) (step, input, mem) ->
                        executeReActStepStreaming(step, input, mem, onToken),
                planningInterval);

        Predicate<StepResult> condition = r -> r.stepNumber() >= maxSteps
                || r.hasError() || memory.hasFinalAnswer();
        String result = loop.run(this, task, condition);

        if (!memory.hasFinalAnswer()) {
            result = provideFinalAnswer(task);
            memory.appendFinalAnswer(result);
        }

        EvaluationResult eval = evaluate(task, result, evaluator, ctx);
        ctx.compress();

        long elapsed = System.currentTimeMillis() - startMs;
        onToken.accept("\n[" + name + " done in " + elapsed + "ms, " + memory.steps().size() + " steps]");
        log.info("Agent '{}' streaming completed in {}ms", name, elapsed);
        return result;
    }

    protected StepResult executeReActStepStreaming(
            int stepNumber, String input, MemoryManager memory,
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

        // ===== Pre-loop: preflight context check =====
        if (ctx.tokenCount() > ctx.maxTokens() * 0.75) {
            log.info("Agent '{}' preflight compression: {} / {} tokens",
                    name, ctx.tokenCount(), ctx.maxTokens());
            ctx.compress();
        }

        // ===== Pre-loop: initialize capabilities (perceive, reason, plan) =====
        initializeCapabilities(task, ctx);

        // ===== ReAct loop with integrated capability hooks per step =====
        AgentLoop<String, String> loop = resolveLoop();
        Predicate<StepResult> condition = r -> r.stepNumber() >= maxSteps
                || r.hasError() || memory.hasFinalAnswer();

        String result = loop.run(this, task, condition);

        if (!memory.hasFinalAnswer()) {
            log.warn("Agent '{}' exceeded max steps, providing fallback answer", name);
            result = provideFinalAnswer(task);
            memory.appendFinalAnswer(result);
        }

        // ===== Post-loop: final evaluation, memory storage, and learning =====
        autoCompact();
        EvaluationResult eval = evaluate(task, result, evaluator, ctx);
        storeMemories(task, result);
        ctx.compress();

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
    private StepResult executeIntegratedStep(int stepNumber, String input, MemoryManager memory) {
        // 1. Pre-step: inject capability context into system prompt
        injectCapabilityContext(stepNumber, memory);

        // 2. Act: delegate to subclass (ToolCallingAgent)
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
    private void initializeCapabilities(String task, Context ctx) {
        // 1. Memory injection from past sessions
        injectMemories(task, ctx);

        // 2. Perception: initial environmental snapshot
        perceiveAndRemember(task, ctx);

        // 3. Reasoning: analyze the task
        ReasoningChain chain = reasoner != null
                ? reasoner.reason(task) : ReasoningChain.empty();
        this.activeReasoning = chain;
        if (!chain.steps().isEmpty()) ctx.addChunk(newChunk("reasoning", chain.summarize()));

        // 4. Planning: generate execution blueprint
        planAndRemember(task, chain, ctx);
    }

    // ============ Per-step capability hooks ============

    /**
     * Inject current reasoning state and plan progress into AgentMemory
     * so the LLM sees them as part of the conversation context.
     */
    private void injectCapabilityContext(int stepNumber, MemoryManager memory) {
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
            memory.remember(ContentStep.systemPrompt(ctx.toString()));
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
                    memory.remember(ContentStep.systemPrompt("[Perception Update]:\n" + data
                            + "\n" + perceptionObserver.buildEnrichedContext()));
                }
            } else {
                PerceptionResult<String> pr = perceptor.perceive(observation);
                String data = pr.data() != null ? pr.data() : "";
                if (!data.isEmpty()) {
                    perceptionHistory.add(truncate(data, 200));
                    memory.remember(ContentStep.systemPrompt("[Perception Update]:\n" + data));
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
        } else if (result.state() == io.sketch.mochaagents.agent.loop.LoopState.ERROR) {
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

    /**
     * Execute a single ReAct step. Subclasses (ToolCallingAgent)
     * implement the actual LLM call and tool/code execution.
     */
    protected abstract StepResult executeReActStep(
            int stepNumber, String input, MemoryManager memory);

    // ============ Internal methods ============

    protected String planStep(int stepNumber, String input, MemoryManager memory) {
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
        try {
            List<MemoryRecord> snapshots = memory.snapshot();
            for (var mem : snapshots) {
                memory.save(mem);
            }
            if (!snapshots.isEmpty()) {
                log.debug("Agent '{}' stored {} memory entries", name, snapshots.size());
            }
        } catch (Exception e) {
            log.debug("Agent '{}' memory storage failed: {}", name, e.getMessage());
        }
    }

    /** Initial perception + memory injection (called once before loop). */
    private void perceiveAndRemember(String input, Context ctx) {
        if (perceptor == null) return;
        PerceptionResult<String> result = perceptor.perceive(input);
        String data = result.data() != null ? result.data() : "";
        if (!data.isEmpty()) {
            memory.remember(ContentStep.systemPrompt("[Initial Perception]:\n" + data));
            perceptionHistory.add(truncate(data, 200));
        }
        ctx.addChunk(newChunk("perception", data));
    }

    /** Initial plan generation (called once before loop). */
    private void planAndRemember(String input, ReasoningChain chain, Context ctx) {
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
                memory.remember(ContentStep.task(line.substring(line.indexOf(' ') + 1).trim()));
            } else if (line.startsWith("Assistant: ") || line.startsWith("assistant: ")) {
                memory.remember(new ActionStep(memory.stepCount() + 1, "",
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

    private int lastSerializedStep = 0;
    private final List<Map<String, String>> cachedMessages = new ArrayList<>();

    /** Convert memory to LLM messages — incremental, O(steps since last call). */
    protected List<Map<String, String>> writeMemoryToMessages() {
        if (cachedMessages.isEmpty() && memory.systemPrompt() != null
                && !memory.systemPrompt().isEmpty()) {
            cachedMessages.add(Map.of("role", "system", "content", memory.systemPrompt()));
        }

        List<MemoryStep> steps = memory.steps();
        int total = steps.size();
        for (int i = lastSerializedStep; i < total; i++) {
            cachedMessages.addAll(stepToMessages(steps.get(i)));
        }
        lastSerializedStep = total;

        log.debug("Agent '{}' messages: {} total ({} new)", name, cachedMessages.size(),
                total - lastSerializedStep > 0 ? total - lastSerializedStep : 0);
        return cachedMessages;
    }

    private static List<Map<String, String>> stepToMessages(MemoryStep step) {
        if (step instanceof ContentStep cs && cs.isSystemPrompt()) {
            return List.of(Map.of("role", "system", "content", cs.text()));
        } else if (step instanceof ContentStep cs && cs.isTask()) {
            return List.of(Map.of("role", "user", "content", cs.text()));
        } else if (step instanceof PlanningStep ps) {
            return List.of(Map.of("role", "assistant", "content", "Plan:\n" + ps.plan()));
        } else if (step instanceof ActionStep as) {
            List<Map<String, String>> msgs = new ArrayList<>();
            if (as.modelOutput() != null && !as.modelOutput().isEmpty())
                msgs.add(Map.of("role", "assistant", "content", as.modelOutput()));
            if (as.observation() != null && !as.observation().isEmpty())
                msgs.add(Map.of("role", "user", "content", "Observation:\n" + as.observation()));
            return msgs;
        }
        return List.of();
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
        // Register self-learning tools (GenericAgent pattern, via AgentMemory)
        if (!toolRegistry.has("update_checkpoint")) {
            toolRegistry.register(new io.sketch.mochaagents.tool.internal.LearnTools.UpdateCheckpoint(memory));
        }
        if (!toolRegistry.has("start_long_term_update")) {
            toolRegistry.register(new io.sketch.mochaagents.tool.internal.LearnTools.SettleLongTerm(memory));
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
        protected io.sketch.mochaagents.llm.LLMRouter router;
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
        protected AgentLoop<String, String> agentLoop;
        protected io.sketch.mochaagents.interaction.PermissionRules permissionRules;

        // Cognitive capabilities (own them, not inherited from BaseAgent)
        protected io.sketch.mochaagents.perception.Perceptor<String, String> perceptor;
        protected io.sketch.mochaagents.reasoning.Reasoner reasoner;
        protected io.sketch.mochaagents.plan.Planner<String> planner;
        protected io.sketch.mochaagents.evaluation.Evaluator evaluator;

        public T llm(LLM llm) { this.llm = llm; return (T) this; }
        public T optimization(io.sketch.mochaagents.llm.OptimizationConfig cfg) { this.optimization = cfg; return (T) this; }
        public T router(io.sketch.mochaagents.llm.LLMRouter router) { this.router = router; return (T) this; }
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
        public T perceptor(io.sketch.mochaagents.perception.Perceptor<String, String> p) { this.perceptor = p; return (T) this; }
        public T reasoner(io.sketch.mochaagents.reasoning.Reasoner r) { this.reasoner = r; return (T) this; }
        public T planner(io.sketch.mochaagents.plan.Planner<String> p) { this.planner = p; return (T) this; }
        public T evaluator(io.sketch.mochaagents.evaluation.Evaluator e) { this.evaluator = e; return (T) this; }

        /** Set a custom execution paradigm (ReAct, Reflexion, ReWOO, TAO, OPAR). */
        public T agentLoop(AgentLoop<String, String> loop) {
            this.agentLoop = loop; return (T) this;
        }
        public T permissionRules(io.sketch.mochaagents.interaction.PermissionRules rules) {
            this.permissionRules = rules; return (T) this;
        }
    }

    // ============ AgentLoopSwitcher ============

    /**
     * Wrapper that delegates to another agent but overrides the execution loop.
     * Allows runtime paradigm switching without rebuilding.
     */
    private static final class AgentLoopSwitcher extends ReActAgent {
        private final ReActAgent delegate;
        private final AgentLoop<String, String> loop;

        AgentLoopSwitcher(ReActAgent delegate, AgentLoop<String, String> loop) {
            super(createBuilder(delegate, loop));
            this.delegate = delegate;
            this.loop = loop;
        }

        private static Builder<?> createBuilder(ReActAgent delegate, AgentLoop<String, String> loop) {
            io.sketch.mochaagents.agent.loop.ToolCallingAgent.Builder b
                    = io.sketch.mochaagents.agent.loop.ToolCallingAgent.builder();
            b.name(delegate.name);
            b.description(delegate.description);
            b.llm(delegate.llm);
            b.maxSteps(delegate.maxSteps);
            b.agentLoop(loop);
            return b;
        }

        @Override protected StepResult executeReActStep(int step, String input, MemoryManager mem) {
            return delegate.executeReActStep(step, input, mem);
        }
        @Override public String buildSystemPrompt() { return delegate.buildSystemPrompt(); }
    }
}
