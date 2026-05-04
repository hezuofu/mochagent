# API Reference

## Package Overview

| Package | Key Classes | Description |
|---------|-------------|-------------|
| `agent` | `Agent<I,O>`, `AgentContext`, `AgentEvents`, `MochaAgent`, `ExecutionReport` | Core agent abstraction |
| `agent.impl` | `BaseAgent<I,O>`, `ToolCallingAgent`, `CodeAgent` | Concrete agent implementations |
| `agent.react` | `ReActAgent`, `AgenticLoop<I,O>`, `StepResult`, `Hooks`, `PlanMode` | ReAct loop engine |
| `agent.react.strategy` | `ReActLoop`, `ReflexionLoop`, `ReWOOLoop`, `ThinkActObserve`, `ObservePlanActReflect` | Execution paradigms |
| `llm` | `LLM`, `LLMRequest`, `LLMResponse`, `StreamingResponse`, `FallbackLLM`, `CostTracker`, `CachingLLM` | LLM abstraction |
| `llm.provider` | `AnthropicLLM`, `OpenAILLM`, `DeepSeekLLM`, `BaseApiLLM`, `OpenAICompatibleLLM`, `LocalLLM`, `QwenLLM` | Provider implementations |
| `llm.router` | `LLMRouter`, `CostOptimizer`, `FallbackStrategy` | Multi-model routing |
| `tool` | `Tool`, `ToolRegistry`, `ToolExecutor`, `ToolResult`, `ConcurrentSafeBatcher` | Tool system |
| `tool.impl` | `BashTool`, `FileReadTool`, `FileWriteTool`, `FileEditTool`, `GlobTool`, `GrepTool`, `WebFetchTool`, `WebSearchTool`, `CalculatorTool`, `BugCheckTool`, `TodoWriteTool`, `AgentTool`, `SkillTool` | Built-in tools |
| `memory` | `AgentMemory`, `Memory`, `MemoryManager`, `MemoryStore`, `InMemoryMemoryStore` | Memory persistence |
| `context` | `ContextManager`, `ContextChunk`, `AutoCompactor`, `LLMContextCompressor`, `CompactConversation` | Context management |
| `reasoning` | `Reasoner`, `ReasoningChain`, `ReasoningStep`, `DefaultReasoner`, `ThinkingConfig`, `EffortLevel`, `RecoveryStateMachine` | Reasoning engine |
| `reasoning.strategy` | `ChainOfThought`, `TreeOfThought`, `GraphOfThought`, `ProgramOfThought` | Reasoning strategies |
| `plan` | `Planner<T>`, `Plan<T>`, `PlanStep`, `DynamicPlanner`, `DependencyGraph` | Planning system |
| `plan.strategy` | `AdaptivePlanner`, `HierarchicalPlanner`, `ReplanningStrategy`, `DefaultPlan` | Planning strategies |
| `perception` | `Perceptor<I,O>`, `PerceptionResult<T>`, `LayeredContextBuilder`, `PerceptionObserver` | Environment perception |
| `perception.processor` | `CodebasePerceptor`, `FileSystemPerceptor`, `BrowserPerceptor`, `TerminalPerceptor` | Perception processors |
| `evaluation` | `Evaluator`, `EvaluationResult`, `CompositeEvaluator` | Quality evaluation |
| `learn` | `Learner<I,O>`, `LearningStrategy`, `Experience<I,O>` | Learning system |
| `orchestration` | `Orchestrator`, `AgentTeam`, `Role`, `OrchestrationStrategy`, `TaskNotification` | Multi-agent orchestration |
| `safety` | `SafetyManager`, `Sandbox`, `NoopSandbox`, `CodeValidator`, `ContentFilter` | Safety checks |
| `skill` | `Skill`, `SkillRegistry`, `SkillManager`, `SkillContext`, `BundledSkill` | Skill system |
| `plugin` | `PluginManager`, `PluginDescriptor`, `PluginBootstrap`, `PluginLoader`, `ExtensionPoint<T>` | Plugin system |
| `cli` | `Main`, `Repl`, `ModelConfig`, `Dispatcher`, `CliCommand` | CLI |
| `interaction.permission` | `PermissionRules`, `DenialTracker` | Permission control |

---

## Agent Interface

### Agent<I,O>

```java
public interface Agent<I, O> {
    // Core
    O execute(I input, AgentContext ctx);
    CompletableFuture<O> executeAsync(I input, AgentContext ctx);

    // Convenience
    default O execute(I input);

    // Metadata
    AgentMetadata metadata();
    void addListener(AgentListener<I, O> listener);
    void removeListener(AgentListener<I, O> listener);

    // Functional composition
    default <T> Agent<T, O> before(Function<T, I> mapper);
    default <T> Agent<I, T> after(Function<O, T> mapper);
    default <T> Agent<I, T> andThen(Agent<O, T> next);
    default Agent<I, O> when(Predicate<I> condition, Agent<I, O> alternative);
    default Agent<I, O> withRetry(int maxAttempts);
    default Agent<I, O> withTimeout(long timeoutMillis);
}
```

### AgentContext

```java
AgentContext ctx = AgentContext.builder()
    .sessionId("sess-001")        // session identifier
    .userId("user-42")            // user identifier
    .userMessage("the task")      // the input task
    .conversationHistory("...")   // past turns
    .metadata("key", "value")     // additional metadata (instructions, role, etc.)
    .build();
```

### AgentEvents

```java
// Subscribe
Runnable unsub = agent.onEvent(e -> {
    switch (e.type()) {
        case AgentEvents.STARTED     -> /* agent.started */ ;
        case AgentEvents.STEP_START  -> /* step.start */ ;
        case AgentEvents.STEP_END    -> /* step.end */ ;
        case AgentEvents.LLM_CALL    -> /* llm.call */ ;
        case AgentEvents.TOOL_CALL   -> /* tool.call — contains diff data */ ;
        case AgentEvents.FINAL_ANSWER-> /* agent.final_answer */ ;
        case AgentEvents.COMPLETED   -> /* agent.completed */ ;
        case AgentEvents.ERROR       -> /* agent.error */ ;
        case AgentEvents.COST        -> /* agent.cost — double[]{cost, inTk, outTk} */ ;
    }
});
unsub.run();  // unsubscribe
```

### ExecutionReport

```java
ExecutionReport report = agent.runAndReport("task");
report.result();         // String
report.steps();          // int
report.elapsedMs();      // long
report.estimatedCost();  // double
report.inputTokens();    // long
report.outputTokens();   // long
report.errors();         // List<String>
report.summary();        // String — formatted one-liner
```

---

## MochaAgent

### Builder

```java
MochaAgent agent = MochaAgent.builder()
    // Identity
    .name("my-agent")              // required
    .description("does things")    // optional

    // Type
    .toolCalling()                 // default — LLM generates tool calls
    .code()                        // LLM writes executable code

    // Core
    .llm(llm)                      // required — any LLM implementation
    .maxSteps(20)                  // default 20
    .systemPrompt("You are...")   // custom system prompt

    // Tools
    .toolRegistry(toolRegistry)
    .addTool(new MyTool())

    // Capabilities (all optional — null = noop)
    .thinkingConfig(ThinkingConfig.adaptive())
    .effortLevel(EffortLevel.HIGH)
    .reasoner(new DefaultReasoner(llm))
    .planner(new DynamicPlanner<>(new AdaptivePlanner(llm)))
    .perceptor(new CodebasePerceptor())
    .evaluator(myEvaluator)
    .memoryManager(new MemoryManager(new InMemoryMemoryStore()))
    .safetyManager(new SafetyManager(new NoopSandbox()))

    // Paradigm
    .loop(new ReflexionLoop<>(null, ReflectionEngine.noop()))
    .reflexionLoop()               // convenience shortcut
    .rewooLoop(rsn, exec, synth)  // convenience shortcut

    // Orchestration
    .orchestrator(orch)

    .build();
```

### Config Object

```java
var config = new MochaAgent.Config();
config.name = "my-agent";
config.agentType = Config.AgentType.CODE;
config.llm = llm;
config.maxSteps = 20;
config.toolRegistry = bootstrap.toolRegistry();
config.reasoner = new DefaultReasoner(llm);
config.planner = new DynamicPlanner<>(new AdaptivePlanner(llm));
config.loop = new ReflexionLoop<>(null, ReflectionEngine.noop());
config.thinkingConfig = ThinkingConfig.adaptive();
config.effortLevel = EffortLevel.HIGH;

// Plugin can override:
bootstrap.pluginLoader().applyExtensions(config);

var agent = MochaAgent.create(config);
```

---

## LLM

### LLM Interface

```java
public interface LLM {
    LLMResponse complete(LLMRequest request);
    CompletableFuture<LLMResponse> completeAsync(LLMRequest request);
    StreamingResponse stream(LLMRequest request);
    String modelName();
    int maxContextTokens();
}
```

### LLMRequest

```java
LLMRequest req = LLMRequest.builder()
    .addMessage("system", "You are helpful")
    .addMessage("user", "What is 2+2?")
    .maxTokens(4096)
    .temperature(0.7)
    .topP(1.0)
    .presencePenalty(0.0)
    .frequencyPenalty(0.0)
    .thinkingConfig(ThinkingConfig.adaptive())
    .effort(EffortLevel.HIGH)
    .build();
```

### LLMResponse

```java
LLMResponse rsp = llm.complete(req);
rsp.content();           // String
rsp.modelName();         // String
rsp.promptTokens();      // int
rsp.completionTokens();  // int
rsp.elapsedMs();         // long
rsp.metadata();          // Map<String,Object>
```

### Providers

```java
// Anthropic — prompt caching enabled by default
AnthropicLLM.builder()
    .modelId("claude-sonnet-4-20250514")
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .build();

// OpenAI
OpenAILLM.builder()
    .modelId("gpt-4o")
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .build();

// DeepSeek — auto reads DEEPSEEK_API_KEY
DeepSeekLLM.deepseekBuilder().modelId("deepseek-chat").build();

// Groq — free tier, fast
OpenAICompatibleLLM.compatibleBuilder()
    .modelId("llama-3.3-70b-versatile")
    .baseUrl("https://api.groq.com/openai/v1")
    .apiKey(System.getenv("GROQ_API_KEY")).build();

// Ollama — local
OpenAICompatibleLLM.forOllama("llama3");

// Fallback — "No LLM configured" message
new FallbackLLM();
```

### Caching + Cost Tracking

```java
// Auto-wrapped in ReActAgent when optimization.cacheMaxEntries() > 0
// Tracks per-model token usage and estimated cost
costTracker.estimatedTotalCost();
costTracker.totalInputTokens();
costTracker.totalOutputTokens();
```

---

## Tool System

### Tool Interface

```java
public interface Tool {
    String getName();
    String getDescription();
    Map<String, ToolInput> getInputs();
    String getOutputType();
    Object call(Map<String, Object> arguments);

    // Safety
    default boolean isConcurrencySafe() { return false; }  // can run in parallel?
    default boolean isDestructive() { return false; }      // abort siblings on error?
    default SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
}
```

### AbstractTool

```java
public class MyTool extends AbstractTool {
    public MyTool() {
        super(builder("my_tool", "Description", SecurityLevel.MEDIUM));
    }
    // override getInputs(), call(), etc.
}
```

### ToolExecutor

```java
ToolExecutor executor = new ToolExecutor(registry, timeoutMs, maxRetries, retryDelayMs);
executor.withHooks(hooks);
executor.withPermissions(permissionRules);
executor.withEvents(eventBus);  // fires TOOL_CALL events with diff data

ToolResult result = executor.execute(toolName, args);
result.isError();
result.output();
result.error();
result.durationMs();
```

### ToolRegistry

```java
ToolRegistry registry = new ToolRegistry();
registry.register(new MyTool());
registry.has("my_tool");         // boolean
registry.get("my_tool");         // Tool
registry.all();                  // List<Tool>
registry.size();                 // int
```

---

## Execution Paradigms

### AgenticLoop<I,O>

```java
public interface AgenticLoop<I, O> {
    O run(Agent<I, O> agent, I input, Predicate<StepResult> condition);
    StepResult step(Agent<I, O> agent, I input, int stepNum);
}
```

### ReActLoop

```java
new ReActLoop<>(
    (step, input, memory) -> planText,     // PlanningFn<I> — nullable
    (step, input, memory) -> stepResult,   // StepExecutor<I>
    planningInterval);                      // 0 = no periodic planning
```

### ReflexionLoop

```java
new ReflexionLoop<>(
    (step, input, memory) -> stepResult,   // StepExecutor<I>
    ReflectionEngine.noop(),               // can inject LLM-powered engine
    (step, result, memory) -> critique,    // Critic — nullable → default
    10);                                   // maxImprovements
```

### ReWOOLoop

```java
new ReWOOLoop<>(
    (task, memory) -> planString,          // Reasoner — build full plan
    (name, args) -> resultString,          // ToolExecutor — execute each tool
    (plan, results, memory) -> answer);    // Synthesizer — combine → final answer
```

### ThinkActObserve

```java
new ThinkActObserve<>(
    (step, input, memory) -> plan,         // PlanningFn<I> — nullable
    (step, input, memory) -> result);      // StepExecutor<I>
```

### ObservePlanActReflect

```java
new ObservePlanActReflect<>(
    (step, input, memory) -> observation,  // Observer<I> — nullable
    (step, input, memory) -> plan,         // PlanningFn<I>
    (step, input, memory) -> result,       // StepExecutor<I>
    ReflectionEngine.noop(),               // ReflectionEngine
    3);                                    // reflectInterval — every N steps
```

---

## Step Types (sealed hierarchy)

```java
MemoryStep                              // sealed interface
├── ContentStep                         // systemPrompt | task | finalAnswer
├── PlanningStep                        // plan text + token usage
├── ActionStep                          // modelOutput + action + observation + tokens
└── ToolCallStep                        // single tool invocation
```

```java
StepResult.builder()
    .stepNumber(n)
    .state(LoopState.ACT)              // ACT | COMPLETE | ERROR
    .action("tool_name")
    .observation("tool output")
    .output("final or intermediate")
    .error(null)
    .durationMs(elapsed)
    .build();
```

---

## Reasoning

### ThinkingConfig

```java
ThinkingConfig.adaptive();                    // model decides budget (Claude 4.6+)
ThinkingConfig.enabled(4096);                 // fixed token budget
ThinkingConfig.disabled();                    // no thinking
ThinkingConfig.resolveForModel(modelId);      // auto-select

thinkingConfig.type();                        // ADAPTIVE | ENABLED | DISABLED
thinkingConfig.budgetTokens();                // 0 for ADAPTIVE/DISABLED
thinkingConfig.toApiParams();                 // → Map<String,Object> for API
```

### EffortLevel

```java
EffortLevel.LOW;        // 0.3 — quick
EffortLevel.MEDIUM;     // 0.6 — balanced
EffortLevel.HIGH;       // 0.85 — comprehensive (default)
EffortLevel.MAX;        // 1.0 — deepest

EffortLevel.resolve(envVar, setting, modelId);
EffortLevel.getDefaultForModel(modelId);
EffortLevel.detectKeywordBoost(userInput, current);  // "ultrathink" → HIGH
```

### RecoveryStateMachine

```java
var recovery = new RecoveryStateMachine();
recovery.beginTurn();                              // → NEXT_TURN
recovery.onMaxOutputTokens();                      // → OverrideTokens(64000)
recovery.onMaxOutputTokens();                      // → InjectMessage("Continue...")
recovery.onPromptTooLong();                        // → Compact()
recovery.onModelError("claude", 500);             // → Fallback()
recovery.completeTurn();                           // → COMPLETED — resets counters

// State
recovery.currentPhase();      // NORMAL | ESCALATED_TOKENS | REACTIVE_COMPACT | GIVE_UP
recovery.isInRecovery();      // true if not NORMAL
recovery.summary();           // formatted debug string
```

### DefaultReasoner

```java
// Default: ChainOfThought → TreeOfThought fallback
new DefaultReasoner(llm);

// Custom strategy chain
new DefaultReasoner(List.of(
    new ChainOfThought(llm),
    new TreeOfThought(llm, 5, 3),     // 5 branches, max depth 3
    new GraphOfThought(llm),
    new ProgramOfThought(llm)));

reasoner.reason(question);           // → ReasoningChain
reasoner.setStrategy(new CoT(llm));  // override
```

---

## Planning

### Planner<T>

```java
public interface Planner<T> {
    Plan<T> generatePlan(PlanningRequest<T> request);
    Plan<T> replan(Plan<T> currentPlan, ExecutionFeedback feedback);
    PlanningStrategy getStrategy();
    void setStrategy(PlanningStrategy strategy);
}
```

### DynamicPlanner

```java
var planner = new DynamicPlanner<>(new AdaptivePlanner(llm));
Plan<String> plan = planner.generatePlan(
    PlanningRequest.<String>builder()
        .goal("Refactor auth module")
        .context(chain.summarize())
        .maxSteps(5).build());
```

### Plan<T>

```java
plan.getPlanId();                // unique ID
plan.getGoal();                  // task description
plan.getSteps();                 // List<PlanStep>
plan.getCurrentStep();           // current step
plan.advance();                  // move to next
plan.serialize();                // → String for memory/display
plan.validate();                 // → ValidationResult
plan.getDependencyGraph();       // → DependencyGraph (Kahn's algorithm for critical path)
```

### PlanStep

```java
PlanStep.builder()
    .stepId("step-1")
    .description("Analyze current auth flow")
    .type(StepType.THINKING)
    .dependencies(List.of())
    .agentId("explorer")           // delegate to this agent
    .priority(5).build();

step.markRunning();
step.markSuccess(result);
step.markFailed(result);
```

---

## Perception

### Perceptor<I,O>

```java
public interface Perceptor<I, O> {
    PerceptionResult<O> perceive(I input);
    CompletableFuture<PerceptionResult<O>> perceiveAsync(I input);
    default Perceptor<I, O> filter(Predicate<O> filter);
}
```

### Built-in Processors

```java
new CodebasePerceptor(200, 5);    // scan project structure
new FileSystemPerceptor();        // read file contents
new BrowserPerceptor();           // web page content
new TerminalPerceptor();          // terminal output
```

### PerceptionObserver

```java
var observer = new PerceptionObserver(ctxBuilder, perceptor1, perceptor2);
observer.observe(task);               // initial snapshot
observer.observeAction(observation);  // per-step update
observer.buildEnrichedContext();      // → String for LLM injection
observer.detectChanges();            // → List<String> of detected diffs
observer.prefetchAsync(input);      // async I/O during tool execution
```

### LayeredContextBuilder

```java
var builder = new LayeredContextBuilder(projectRoot, osName, modelName, claudeMdPaths);
builder.buildFullContext(staticPrompt, dynamicPrompt);  // 3-layer combined
builder.buildUserContext();   // <system-reminder> block
builder.getSystemContext();   // git + platform + model
builder.invalidateCache();    // refresh on branch change / midnight crossing
```

---

## Context Management

### ContextManager

```java
ContextManager cm = new ContextManager(maxTokens, strategy, compressor);
cm.addChunk(chunk);
cm.tokenCount();
cm.compress();                // triggers compressor if set
cm.all();                     // current chunks
```

### AutoCompactor

```java
AutoCompactor ac = new AutoCompactor(ctxManager, 8192);
ac.checkAndCompact();         // triggers at 80% threshold
ac.trackFile("/path/to/f");   // restore this file after compaction
ac.compactionCount();         // how many times compacted
```

### ContextCompressor

```java
// No-op (default)
ContextCompressor none = (chunks, maxT) -> chunks;

// LLM-powered (wired in ReActAgent)
ContextCompressor real = new LLMContextCompressor(llm);
```

---

## Memory

### Memory Interface

```java
public interface Memory {
    String content();        // the memory text
    String type();           // EPISODIC | SEMANTIC | PROCEDURAL
    double importance();     // 0.0-1.0
    Set<String> concepts();  // associated keywords
}
```

### MemoryManager

```java
MemoryManager mm = new MemoryManager(new InMemoryMemoryStore());
mm.store(memory);                    // persist
mm.storeWithIndex(memory, title, hook, fileName);
mm.search("auth module");            // semantic search
mm.clear();

// Auto-wired in ReActAgent:
//   injectMemories() called pre-loop — retrieves past memories
//   storeMemories() called post-loop — saves AgentMemory.snapshot()
```

### AgentMemory

```java
AgentMemory memory = agent.memory();
memory.reset(systemPrompt);
memory.appendTask(task);
memory.appendPlanning(plan, "", 0, 0);
memory.appendAction(actionStep);
memory.appendFinalAnswer(result);
memory.hasFinalAnswer();
memory.steps();           // List<MemoryStep> — full execution trace
memory.snapshot();        // → List<Memory> for MemoryManager persistence
memory.restore(entries);  // reload from MemoryManager
```

---

## Hooks & Permissions

### Hooks

```java
Hooks hooks = agent.hooks();

// Pre-tool — can deny or modify arguments
hooks.onPreTool((tool, args) -> {
    if (tool.isDestructive()) return HookDecision.deny("destructive");
    return HookDecision.allow(args);
});

// Post-tool — inspect results after execution
hooks.onPostTool((tool, args, result) -> { /* log, notify, etc. */ });

// Stop hook — check if agent should stop
hooks.onStop(ctx -> StopDecision.CONTINUE);

// Tool name matchers
hooks.onPreToolMatching("bash", (tool, args) -> HookDecision.ask(args));
```

### HookDecision

```java
HookDecision.allow(args);           // proceed
HookDecision.allow(args).modifyArgs(newArgs);  // proceed with modified args
HookDecision.deny("reason");        // block + error message
HookDecision.ask(args);             // prompt user
```

### PermissionRules

```java
var rules = new PermissionRules()
    .add("rm*", Behavior.DENY, Source.POLICY)           // wildcards
    .add("bash(git *)", Behavior.ALLOW, Source.USER)    // content-specific
    .add("mcp__dangerous/*", Behavior.DENY, Source.LOCAL)  // MCP tools
    .defaultBehavior(Behavior.ASK);

// Source priority: POLICY > LOCAL > PROJECT > USER
rules.resolve("rm");
rules.resolve("rmdir");
rules.resolve("bash(git status)");
```

---

## PlanMode

```java
PlanMode pm = new PlanMode();

// State machine
pm.enterPlan();                           // DEFAULT → PLAN (read-only)
pm.advancePhase();                        // UNDERSTAND → DESIGN → REVIEW → FINAL_PLAN
pm.exitPlan(content);                     // PLAN → APPROVED (write-enabled)
pm.cancelPlan();                          // PLAN → DEFAULT (user cancelled)
pm.reenterPlan();                         // APPROVED → PLAN (re-evaluate)

// Queries
pm.state();                               // DEFAULT | PLAN | APPROVED
pm.currentPhase();                        // UNDERSTAND | DESIGN | REVIEW | FINAL_PLAN | EXIT
pm.isReadOnly();                          // true when PLAN
pm.currentPlan();                         // String — current plan content

// Persistence
pm.loadPlan("slug");                      // read from disk
pm.loadCurrentPlan();                     // read current
pm.listPlans();                           // all saved plans
pm.recoverPlan();                         // try disk → memory → transcript
pm.appendToPlan("more content");          // incremental update

// Parallel exploration
pm.setExploreAgentCount(3);
pm.setPlanAgentCount(1);
pm.registerExploreAgent("id", "focus");
pm.allExploreAgentsDone();
pm.collectExploreFindings();
```

---

## Plugin System

### ExtensionPoint

```java
// 9 types: TOOL, PERCEPTOR, REASONER, PLANNER, EVALUATOR, HOOK, SKILL, LOOP, MCP_SERVER

ExtensionPoint.tool(new MyTool(), 10);
ExtensionPoint.perceptor(new MyPerceptor(), 5);
ExtensionPoint.reasoner(new MyReasoner(), 5);
ExtensionPoint.planner(new MyPlanner(), 5);
ExtensionPoint.evaluator(new MyEvaluator(), 5);
ExtensionPoint.loop(new ReflexionLoop<>(...), 5);
ExtensionPoint.mcpServer("npx @my/server", 0);
```

### PluginDescriptor

```java
PluginDescriptor.builder("plugin-name", "Description")
    .version("1.0.0")
    .skills(List.of(skill1, skill2))
    .extensionPoints(List.of(
        ExtensionPoint.tool(new MyTool(), 10)))
    .defaultEnabled(true)
    .isAvailable(() -> checkDependencyExists())
    .build();
```

### PluginLoader

```java
var loader = new PluginLoader(pluginsDir, toolRegistry);
loader.discoverAll();                     // scan plugin.json files
loader.plugins();                         // discovered plugins
loader.applyExtensions(agentConfig);      // inject best perceptor/reasoner/etc.

// Auto-ran in AgentBootstrap.init()
// Auto-applies tools and MCP servers immediately
```

### PluginManager

```java
PluginManager pm = pluginBootstrap.pluginManager();
pm.register(descriptor);
pm.enable("name");
pm.disable("name");
pm.isEnabled("name");
pm.getPlugins();                          // PluginLoadResult — enabled + disabled
pm.getEnabledSkills();                    // List<Skill>
pm.getSkillsByPlugin();                   // Map<String, List<Skill>>
```

---

## CLI

### Main Entry

```java
// E:/ai-work/mochagent/src/main/java/io/sketch/mochaagents/cli/Main.java
public static void main(String[] args);
public static int launch(String[] args, PrintStream out, PrintStream err);
```

### ModelConfig

```java
ModelConfig cfg = new ModelConfig();
cfg.model("claude-sonnet", "anthropic");
cfg.temperature(0.3);
cfg.maxTokens(8192);
cfg.debug(true);
LLM llm = cfg.build();  // single model or LLMRouter
```

### Dispatcher

```java
Dispatcher d = new Dispatcher()
    .on("mcp", mcpHandler)
    .on(List.of("plugin", "plugins"), pluginHandler)
    .on("doctor", doctorHandler)
    .otherwise(replHandler);
d.dispatch(args, out, err);
```

### CliCommand

```java
@FunctionalInterface
public interface CliCommand {
    int run(String[] args, PrintStream out, PrintStream err);
}
```
