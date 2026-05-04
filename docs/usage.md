# Detailed Usage Guide

## Contents

1. [Quick Start](#quick-start)
2. [Agent Types](#agent-types)
3. [Execution Paradigms](#execution-paradigms)
4. [Capability Injection](#capability-injection)
5. [Multi-Agent Orchestration](#multi-agent-orchestration)
6. [Tool System](#tool-system)
7. [LLM Configuration](#llm-configuration)
8. [Context & Memory](#context--memory)
9. [Permission & Hooks](#permission--hooks)
10. [Plan Mode](#plan-mode)
11. [Streaming & Events](#streaming--events)
12. [Plugin System](#plugin-system)
13. [CLI Reference](#cli-reference)

---

## Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>io.sketch.mochaagents</groupId>
    <artifactId>mochaagents</artifactId>
    <version>1.0.0</version>
</dependency>
```

### One-Line Agent

```java
import io.sketch.mochaagents.agent.MochaAgent;
import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.provider.AnthropicLLM;

var llm = AnthropicLLM.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY")).build();

String answer = MochaAgent.builder()
    .name("assistant").llm(llm)
    .build()
    .run("What is the capital of France?");
```

### Auto-Detect LLM

```java
import io.sketch.mochaagents.examples.LLMFactory;

var llm = LLMFactory.create();
// Detects: OPENAI_API_KEY → ANTHROPIC_API_KEY → GROQ → DeepSeek → Ollama
```

### Full Bootstrap

```java
import io.sketch.mochaagents.AgentBootstrap;
import io.sketch.mochaagents.agent.MochaAgent;

var bootstrap = AgentBootstrap.init(llm);
var agent = MochaAgent.builder()
    .name("assistant").llm(llm)
    .toolRegistry(bootstrap.toolRegistry())
    .build();
```

---

## Agent Types

### ToolCalling Agent

LLM generates `Thought: ... Action: tool_name(args)` format:

```java
var agent = MochaAgent.builder()
    .name("tool-agent").llm(llm).toolCalling()
    .addTool(new WebSearchTool())
    .maxSteps(10).build();

// LLM output format:
//   Thought: I need to search for that
//   Action: web_search(query="weather Tokyo")
//   → receives Observation → next step or final_answer
```

### Code Agent

LLM writes code in `<code>` blocks, agent executes it:

```java
var agent = MochaAgent.builder()
    .name("coder").llm(llm).code()
    .maxSteps(15).build();

// LLM output format:
//   <code>
//   import math
//   result = math.sqrt(125)
//   final_answer(str(result))
//   </code>
```

---

## Execution Paradigms

### ReAct (default)

```java
MochaAgent.builder().name("a").llm(llm).build();
// Think → Act → Observe → loop
// Best for: interactive tasks needing environment feedback
```

### Reflexion

```java
MochaAgent.builder().name("a").llm(llm)
    .reflexionLoop().build();
// Act → Self-Critique → Improvement → loop with better strategy
// Best for: error-prone tasks where learning from mistakes matters
```

### ReWOO

```java
MochaAgent.builder().name("a").llm(llm)
    .rewooLoop(reasoner, toolExecutor, synthesizer).build();
// Reason(once) → BatchExecute(all) → Synthesize
// Best for: well-defined tasks with clear plan, fewer LLM calls
```

### Custom Loop

```java
MochaAgent.builder().name("a").llm(llm)
    .loop(new ObservePlanActReflect<>(observer, planner, executor, engine, 3))
    .build();
```

### Runtime Switch

```java
MochaAgent agent = MochaAgent.builder().name("a").llm(llm).build();
MochaAgent reflective = agent.withLoop(
    new ReflexionLoop<>(null, ReflectionEngine.noop()));
```

---

## Capability Injection

All capabilities are optional — null means the hook is a no-op.

### Reasoning

```java
import io.sketch.mochaagents.reasoning.DefaultReasoner;

MochaAgent.builder().name("a").llm(llm)
    .reasoner(new DefaultReasoner(llm))  // CoT → TreeOfThought fallback
    .build();
```

### Planning

```java
import io.sketch.mochaagents.plan.DynamicPlanner;
import io.sketch.mochaagents.plan.strategy.AdaptivePlanner;

MochaAgent.builder().name("a").llm(llm)
    .planner(new DynamicPlanner<>(new AdaptivePlanner(llm)))
    .build();
// Agent will: generate plan → track progress → replan on deviation
```

### Perception

```java
import io.sketch.mochaagents.perception.processor.CodebasePerceptor;

MochaAgent.builder().name("a").llm(llm)
    .perceptor(new CodebasePerceptor(200, 5))
    .build();
// Agent will: perceive codebase before loop → perceive after each tool call
```

### Thinking Control

```java
import io.sketch.mochaagents.reasoning.ThinkingConfig;
import io.sketch.mochaagents.reasoning.EffortLevel;

MochaAgent.builder().name("a").llm(llm)
    .thinkingConfig(ThinkingConfig.adaptive())  // model decides thinking budget
    .effortLevel(EffortLevel.MAX)               // deepest reasoning
    .build();
```

### Evaluation

```java
import io.sketch.mochaagents.evaluation.Evaluator;

MochaAgent.builder().name("a").llm(llm)
    .evaluator(myEvaluator)  // custom quality assessment
    .build();
```

### Memory Persistence

```java
import io.sketch.mochaagents.memory.MemoryManager;
import io.sketch.mochaagents.memory.InMemoryMemoryStore;

MochaAgent.builder().name("a").llm(llm)
    .memoryManager(new MemoryManager(new InMemoryMemoryStore()))
    .build();
// Agent will: inject past memories → auto-store after completion
```

### Safety

```java
import io.sketch.mochaagents.safety.SafetyManager;

MochaAgent.builder().name("a").llm(llm)
    .safetyManager(new SafetyManager(new NoopSandbox()))
    .build();
```

### All Together

```java
MochaAgent agent = MochaAgent.builder()
    .name("full-agent").code().llm(llm)
    .reasoner(new DefaultReasoner(llm))
    .planner(new DynamicPlanner<>(new AdaptivePlanner(llm)))
    .perceptor(new CodebasePerceptor())
    .thinkingConfig(ThinkingConfig.adaptive())
    .effortLevel(EffortLevel.HIGH)
    .evaluator(myEvaluator)
    .memoryManager(new MemoryManager(new InMemoryMemoryStore()))
    .safetyManager(new SafetyManager(new NoopSandbox()))
    .reflexionLoop()
    .maxSteps(20)
    .build();
```

---

## Multi-Agent Orchestration

### Managed Agents (Tool Delegate)

```java
var engineer = ToolCallingAgent.builder().name("engineer").llm(llm).build();
var reviewer = ToolCallingAgent.builder().name("reviewer").llm(llm).build();

var lead = ToolCallingAgent.builder().name("lead").llm(llm)
    .managedAgents(List.of(engineer, reviewer))
    .build();
// LLM can call: delegate_engineer(task="refactor auth")
```

### Agent Composition (Pipeline)

```java
MochaAgent pipeline = MochaAgent.builder().name("step1").llm(llm).build()
    .andThen(MochaAgent.builder().name("step2").llm(llm).build());
```

### Orchestrator

```java
import io.sketch.mochaagents.orchestration.DefaultOrchestrator;
import io.sketch.mochaagents.orchestration.Role;

var orch = new DefaultOrchestrator();
orch.register(engineer.inner(), Role.worker("engineer"));
orch.register(reviewer.inner(), Role.worker("reviewer"));

var result = orch.orchestrate("Build feature X",
    OrchestrationStrategy.sequential());
```

---

## Tool System

### Built-in Tools

Registered by `AgentBootstrap.init(llm)`:

| Tool | Name | Category |
|------|------|----------|
| Bash | `bash` | Shell execution |
| FileRead | `read` | File I/O |
| FileWrite | `write` | File I/O |
| FileEdit | `edit` | File I/O |
| Glob | `glob` | File search |
| Grep | `grep` | Content search |
| WebFetch | `web_fetch` | Web |
| WebSearch | `web_search` | Web |
| Calculator | `calculator` | Math |
| BugCheck | `bug_check` | Analysis |
| TodoWrite | `todo_write` | Task tracking |
| Agent | `agent` | Sub-agent spawn |

### Custom Tool

```java
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.AbstractTool;

public class WeatherTool extends AbstractTool {
    public WeatherTool() {
        super(builder("get_weather", "Get current weather for a city",
                SecurityLevel.LOW));
    }

    @Override
    public Map<String, ToolInput> getInputs() {
        return Map.of("city", ToolInput.string("City name"));
    }

    @Override
    public Object call(Map<String, Object> args) {
        String city = (String) args.getOrDefault("city", "London");
        return fetchWeather(city);  // your implementation
    }
}

// Register
bootstrap.toolRegistry().register(new WeatherTool());
```

### Concurrency Safety

```java
public class ReadOnlyTool extends AbstractTool {
    @Override public boolean isConcurrencySafe() { return true; }   // parallel
}

public class WriteTool extends AbstractTool {
    @Override public boolean isConcurrencySafe() { return false; }  // serial
    @Override public boolean isDestructive() { return true; }       // abort siblings
}
```

---

## LLM Configuration

### Direct Provider

```java
// Anthropic
var llm = AnthropicLLM.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .modelId("claude-sonnet-4-20250514")
    .build();

// OpenAI
var llm = OpenAILLM.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .build();

// DeepSeek
var llm = DeepSeekLLM.create();

// Ollama (local)
var llm = LocalLLM.create();
```

### Multi-Model Router

```java
var router = new LLMRouter(new CostOptimizer(), new FallbackStrategy());
router.register("gpt-4o-mini", cheapLlm);
router.register("claude-sonnet", powerfulLlm);
// Router selects cheapest model that can handle the task

var agent = ToolCallingAgent.builder().name("a")
    .router(router).build();
```

### Auto-Detect

```java
var llm = LLMFactory.create();
// Priority: OPENAI → Anthropic → Groq → DeepSeek → Qwen → HF → Ollama → Fallback
```

---

## Context & Memory

### AgentContext

```java
AgentContext ctx = AgentContext.builder()
    .sessionId("session-abc")
    .userId("user-42")
    .userMessage("Fix the login timeout bug")
    .conversationHistory("""
        User: Where is the auth code?
        Assistant: AuthModule.java, line 42-89""")
    .metadata(Map.of(
        "instructions", "Use TDD approach",
        "role", "Senior Java Developer"))
    .build();

agent.run(ctx);
```

### Memory Injection

```java
var memoryManager = new MemoryManager(new InMemoryMemoryStore());
memoryManager.store(MemoryEntry.builder()
    .content("AuthModule.java has a race condition at line 67")
    .type(Memory.TYPE_EPISODIC)
    .importance(0.8).build());

var agent = MochaAgent.builder().name("a").llm(llm)
    .memoryManager(memoryManager).build();
// Past memories automatically injected as context
// New memories automatically stored after completion
```

### Context Compaction

```java
// Manual
if (agent.inner() instanceof ReActAgent ra) {
    ra.autoCompact();  // triggers LLMContextCompressor
}

// Auto: triggers at 80% context window threshold
```

---

## Permission & Hooks

### Permission Rules

```java
var rules = new PermissionRules()
    .add("rm*", Behavior.DENY, Source.POLICY)      // block all remove tools
    .add("bash(git *)", Behavior.ALLOW, Source.USER) // allow git commands
    .defaultBehavior(Behavior.ASK);

var agent = ToolCallingAgent.builder().name("a").llm(llm)
    .permissionRules(rules).build();
// Permission checked in executeTool() pipeline before every tool call
```

### Tool Hooks

```java
var agent = ToolCallingAgent.builder().name("a").llm(llm).build();

// Pre-tool: intercept before execution
agent.hooks().onPreTool((tool, args) -> {
    if ("bash".equals(tool.getName())
        && args.toString().contains("sudo"))
        return HookDecision.deny("sudo blocked by policy");
    return HookDecision.allow(args);
});

// Post-tool: inspect results
agent.hooks().onPostTool((tool, args, result) -> {
    System.out.println("Tool " + tool.getName() + " completed");
});
```

---

## Plan Mode

```java
var planMode = new PlanMode();

// Phase 1: UNDERSTAND (read-only)
planMode.enterPlan();
// Agent explores codebase, launches Explore agents

// Phase 2 → DESIGN
planMode.advancePhase();
// Agent designs alternatives

// Phase 3 → REVIEW
planMode.advancePhase();
// Read critical files, resolve ambiguities

// Phase 4 → FINAL_PLAN
planMode.advancePhase();
// Write plan to disk

// Phase 5 → EXIT
planMode.exitPlan(planContent);
// User approves → agent enters implementation mode

// Persistence
planMode.loadPlan("brave-lion-42");   // load by slug
planMode.listPlans();                 // list all saved
planMode.recoverPlan();              // resume from disk on session restart
```

---

## Streaming & Events

### Streaming

```java
agent.runStreaming(ctx, token -> {
    System.out.print(token);
});
```

### Event Subscription

```java
var agent = ToolCallingAgent.builder().name("a").llm(llm).build();

agent.onEvent(e -> {
    switch (e.type()) {
        case AgentEvents.STARTED:
            System.out.println("Agent started: " + e.agentName());
            break;
        case AgentEvents.TOOL_CALL: {
            var data = (Map<String, Object>) e.data();
            String tool = (String) data.get("toolName");
            String file = (String) data.get("file");
            if (file != null) {
                // File-modifying tool — show diff
                System.out.println("│ ~ " + file);
                String oldC = (String) data.get("oldContent");
                String newC = (String) data.get("newContent");
                showDiff(oldC, newC);
            }
            break;
        }
        case AgentEvents.COST: {
            double[] c = (double[]) e.data();
            System.out.printf("$%.4f | %dtk%n", c[0], (long) c[1]);
            break;
        }
        case AgentEvents.COMPLETED:
            System.out.println("Done in " + e.elapsedMs() + "ms");
            break;
    }
});
```

---

## Plugin System

### Directory Structure

```
~/.mocha/plugins/
  my-plugin/
    plugin.json
    lib/
      my-plugin.jar
```

### plugin.json

```json
{
  "name": "my-plugin",
  "version": "1.0.0",
  "description": "Custom tool collection",
  "enabled": true,
  "extensions": [
    {"type": "TOOL", "className": "com.example.SecurityScanner", "priority": 10},
    {"type": "PERCEPTOR", "className": "com.example.DependencyPerceptor", "priority": 5},
    {"type": "MCP_SERVER", "className": "npx @my/mcp-server", "priority": 0}
  ]
}
```

### Loading

```java
// Automatic — AgentBootstrap.init() calls PluginLoader.discoverAll()
var bootstrap = AgentBootstrap.init(llm);

// Manual injection into agent config
var config = new MochaAgent.Config();
bootstrap.pluginLoader().applyExtensions(config);
var agent = MochaAgent.create(config);
// Plugin perceptor/reasoner/planner/evaluator/loop injected with priority resolution
```

### Built-in Plugin (Skill Group)

```java
var desc = PluginDescriptor.builder("git-utilities",
        "Git commit, review, and code explanation tools")
    .version("1.0")
    .skills(List.of(commitSkill, reviewSkill, explainSkill))
    .extensionPoints(List.of(
        ExtensionPoint.tool(new BugCheckTool(), 5)))
    .build();

pluginManager.register(desc);
```

---

## CLI Reference

```bash
# Start interactive REPL
mocha
mocha --model claude-sonnet-4-20250514
mocha --model gpt-4o-mini --temperature 0.3 --max-tokens 8192

# Subcommands
mocha mcp serve              # Start MCP JSON-RPC server on stdio
mocha plugin                 # List plugins
mocha doctor                 # Diagnostics
mocha update                 # Version check
mocha --version              # Print version

# REPL slash commands
> /help           Show all commands
> /model          Show current model info
> /cost           Show session cost + token usage
> /clear          Clear conversation context
> /compact        Trigger context compaction
> /plan           Enter plan mode (read-only)
> /exitplan       Exit plan mode
> /diff [file]    Show pending file changes
> /status         Show agent status
> /tools          List available tools
> /exit           Quit
```
