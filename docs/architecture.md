# MochaAgent Architecture

## Design Philosophy

- **Strategy over Inheritance**: capabilities are injected via Builder, not deep class hierarchies
- **Null = No-op**: every optional component defaults to null → corresponding hook is a no-op
- **Single Entry**: `MochaAgent` facade implements `Agent<String,String>`, composable in all contexts
- **Pluggable Paradigms**: execution strategy (ReAct/Reflexion/ReWOO) is a field, not a class

## Layer Architecture

```
┌──────────────────────────────────────────────────┐
│  MochaAgent (facade)                             │
│  implements Agent<String,String>                 │
│  .run() .runAndReport() .runStreaming()          │
│  .withLoop()  .inner()                           │
├──────────────────────────────────────────────────┤
│  ReActAgent (execution engine)                   │
│  Pre-loop: perceive → reason → plan              │
│  Loop:     [injectCtx → step → perceive → track] │
│  Post-loop: evaluate → storeMemories → learn     │
├────────────┬────────────┬────────────────────────┤
│ Perceptor  │ Reasoner   │ Planner                │
│ (optional) │ (optional) │ (optional)             │
│ perceive() │ reason()   │ generatePlan()         │
│            │            │ replan()               │
├────────────┴────────────┴────────────────────────┤
│  ToolCallingAgent  │  CodeAgent                  │
│  Thought/Action    │  <code>...</code>           │
├────────────────────┴─────────────────────────────┤
│  AgenticLoop (pluggable paradigm)                │
│  ReActLoop │ ReflexionLoop │ ReWOOLoop │ TAO │ OPAR│
├──────────────────────────────────────────────────┤
│  ToolExecutor (execution pipeline)               │
│  Permission→PreHooks→Execute→PostHooks→Event     │
├──────────────────────────────────────────────────┤
│  ContextManager │ AgentMemory │ CostTracker      │
│  AutoCompactor  │ MemoryManager │ RecoverySM     │
└──────────────────────────────────────────────────┘
```

## Core Interfaces

### Agent<I,O>

```java
public interface Agent<I, O> {
    O execute(I input, AgentContext ctx);
    CompletableFuture<O> executeAsync(I input, AgentContext ctx);
    AgentMetadata metadata();

    // Functional composition
    default <T> Agent<T, O> before(Function<T, I> mapper);
    default <T> Agent<I, T> after(Function<O, T> mapper);
    default <T> Agent<I, T> andThen(Agent<O, T> next);
    default Agent<I, O> when(Predicate<I> condition, Agent<I, O> alternative);
    default Agent<I, O> withRetry(int maxAttempts);
    default Agent<I, O> withTimeout(long timeoutMillis);
}
```

### AgenticLoop<I,O>

```java
public interface AgenticLoop<I, O> {
    O run(Agent<I, O> agent, I input, Predicate<StepResult> condition);
    StepResult step(Agent<I, O> agent, I input, int stepNum);
}
```

### ExtensionPoint<T>

```java
public interface ExtensionPoint<T> {
    String type();      // TOOL | PERCEPTOR | REASONER | PLANNER | EVALUATOR | LOOP | MCP_SERVER
    String description();
    T component();
    int priority();     // higher wins when multiple plugins provide same type
}
```

## Inheritance Chain

```
Agent<I,O>                         ← functional composition
  └── BaseAgent<I,O>               ← Template Method + 9 optional capabilities
       └── ReActAgent              ← ReAct loop + LLM + tools + event bus
            ├── ToolCallingAgent   ← Thought/Action format
            └── CodeAgent          ← code block execution
```

4 levels — no deeper. `MochaAgent` is a `Agent<String,String>` wrapper, not a subclass.

## Execution Paradigm Flow

### ReAct (default)
```
┌──────┐    ┌──────┐    ┌──────────┐
│THINK │ →  │ ACT  │ →  │ OBSERVE  │ → (loop)
└──────┘    └──────┘    └──────────┘
LLM call    tool.call()  record result
```

### Reflexion
```
┌──────┐    ┌───────────┐    ┌──────────┐
│ ACT  │ →  │ CRITIQUE  │ →  │ IMPROVE  │ → (loop with better strategy)
└──────┘    └───────────┘    └──────────┘
```

### ReWOO
```
┌─────────┐    ┌───────────────┐    ┌─────────────┐
│ REASON  │ →  │ BATCH EXECUTE │ →  │ SYNTHESIZE  │  (2 LLM calls total)
└─────────┘    └───────────────┘    └─────────────┘
full plan     all tools at once     final answer
```

## Tool Execution Pipeline

```
executeTool(name, args)
  │
  ├── PermissionRules.resolve(name)   ←  deny → fail
  ├── Hooks.applyPreTool(tool, args)   ←  deny → fail / modify args
  ├── tool.call(args)                  ←  timeout + retry (maxRetries)
  ├── Hooks.applyPostTool(tool, args, result)
  └── AgentEvents.fire(TOOL_CALL)     ←  diff data for real-time display
```

## Context System (3-layer, claude-code pattern)

```
Layer 1: SYSTEM PROMPT (static + DYNAMIC_BOUNDARY + dynamic)
         Global cacheable prefix │ session-scoped suffix

Layer 2: SYSTEM CONTEXT (memoized per session)
         gitBranch + gitStatus + recentCommits + platform + model

Layer 3: USER CONTEXT (memoized per session)
         CLAUDE.md content + currentDate
         Injected as <system-reminder> block
```

## Plugin Extension Architecture

```
AgentBootstrap.init(llm)
  ├── SkillManager.bootstrap(toolRegistry)     ← bundled skills
  ├── PluginBootstrap.bootstrap(skillRegistry)  ← builtin plugins
  ├── AgentTool.registerDefaultAgent()           ← sub-agent spawning
  ├── registerBaseTools()                        ← 12 built-in tools
  └── PluginLoader.discoverAll()                ← ~/.mocha/plugins/

PluginLoader.applyExtensions(config)
  └── Perceptor/Reasoner/Planner/Evaluator/Loop injected by priority
```

## Recovery Chain (claude-code pattern)

```
NORMAL
  ├── max_output_tokens → ESCALATE_TOKENS (64k)
  │   └── still hitting → RESUME_INJECT (3x "Continue — no apology")
  ├── prompt_too_long → REACTIVE_COMPACT (summarize history)
  │   └── still too long → GIVE_UP
  └── api_error → MODEL_FALLBACK
      └── still failing → GIVE_UP
```

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Null = No-op | Builder fields default null → capability hooks are no-op. Agent works with zero config |
| AgenticLoop as field, not subclass | Paradigm switch without new agent type. `.loop(new ReflexionLoop(...))` |
| ToolExecutor as unified pipeline | Single place for permission/hooks/timeout/retry/events — no bypass |
| ThinkingConfig flows to API | LLMRequest → AnthropicLLM.buildRequestBody() → API thinking param |
| MochaAgent implements Agent | Can participate in `andThen`/`withRetry`/`withTimeout` chains |
| PluginLoader priority injection | Highest-priority extension wins → clean override mechanism |
| Sealed interface Message hierarchy | Type-safe pattern matching on message types |
