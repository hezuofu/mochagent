# MochaAgents

Java AI agent framework — reimplementation of [smolagents](https://github.com/huggingface/smolagents) with production-grade multi-agent capabilities.

## Quick Start

```bash
mvn clean compile
```

```java
// ToolCallingAgent with any LLM provider
var llm = DeepSeekLLM.builder().apiKey(System.getenv("DEEPSEEK_API_KEY")).build();

var agent = ToolCallingAgent.builder()
        .llm(llm)
        .maxSteps(10)
        .build();

String answer = agent.run("What is the capital of France?");
```

```java
// CodeAgent — LLM writes code, agent executes it
var agent = CodeAgent.builder()
        .llm(llm)
        .maxSteps(15)
        .build();

String answer = agent.run("Calculate the 20th Fibonacci number");
```

```java
// AgentContext — session, user, conversation history in one parameter
AgentContext ctx = AgentContext.builder()
        .sessionId("sess-001")
        .userId("user-42")
        .userMessage("What's the weather?")
        .conversationHistory("User: hello\nAssistant: Hi!")
        .metadata("instructions", "Answer concisely")
        .build();

agent.run(ctx);
```

## Architecture

```
Agent<I,O>  (interface — sync/async, functional composition)
  └── BaseAgent<I,O>  (Template Method + 9 optional capability fields)
       └── MultiStepAgent  (ReAct loop + LLM + tools)
            ├── ToolCallingAgent  (tool-calling via structured output)
            └── CodeAgent         (code-execution via ScriptEngine)
```

Key inheritance chain is 4 levels — lean and modular.

## Modules

| Module | Status | Description |
|--------|--------|-------------|
| **agent** | ACTIVE | Core Agent, BaseAgent, MultiStepAgent, CodeAgent, ToolCallingAgent, CompositeAgent |
| **agent/loop** | ACTIVE | ReActLoop, ThinkActObserve, ObservePlanActReflect, Step types, Termination |
| **llm** | ACTIVE | LLM interface, OpenAI, Anthropic, DeepSeek, Qwen, Groq, Ollama, Mock, Router |
| **tool** | ACTIVE | Tool interface, Registry, Bash, PowerShell, File I/O, WebSearch, WebFetch, Grep, Glob |
| **memory** | ACTIVE | AgentMemory, MemoryManager, InMemoryStore, MarkdownStore (file-persisted) |
| **context** | ACTIVE | ContextManager, SlidingWindow, Summarization (LLM), Hybrid (LLM), LLMContextCompressor |
| **reasoning** | ACTIVE | ChainOfThought, TreeOfThought, ProgramOfThought, GraphOfThought (all LLM-powered) |
| **plan** | ACTIVE | DynamicPlanner, AdaptivePlanner, HierarchicalPlanner, ReplanningStrategy, SemanticDecomposer |
| **perception** | ACTIVE | CodebasePerceptor, FileSystemPerceptor, BrowserPerceptor, TerminalPerceptor, CompositePerceptor |
| **evaluation** | ACTIVE | CompositeEvaluator, AutomatedJudge, LLMJudge, HumanJudge, Quality/Safety/Hallucination metrics |
| **learn** | ACTIVE | ExperienceBuffer, ExperienceReplay, FewShotLearner, CurriculumLearner, FeedbackLearner |
| **orchestration** | ACTIVE | DefaultOrchestrator, AgentTeam, DebateStrategy, SwarmStrategy, SupervisorAgent, MessageBus |
| **safety** | ACTIVE | SafetyManager, CodeValidator, ContentFilter, Sandbox, PolicyEnforcer, AuditLogger |
| **interaction** | ACTIVE | AutonomousMode (message queue), CollaborativeMode, SupervisedMode, PermissionManager |
| **skill** | ACTIVE | SkillRegistry, SkillsInit, BundledSkill (commit/review/explain/bug-check), FileSystemSkillLoader |
| **plugin** | ACTIVE | PluginManager, PluginBootstrap (git-utilities plugin), PluginDescriptor |
| **monitor** | ACTIVE | Tracer, MetricsCollector, Telemetry |
| **cli** | ACTIVE | CLI Bootstrap, REPL (real agent execution), AgentBootstrap startup chain |
| **prompt** | UTILITY | PromptTemplate (placeholder rendering) |

## LLM Providers

| Provider | Class | Free Tier |
|----------|-------|-----------|
| OpenAI | `OpenAILLM` | — |
| Anthropic | `AnthropicLLM` | — |
| **DeepSeek** | `DeepSeekLLM` | Free quota |
| **Groq** | `OpenAICompatibleLLM` | Free tier (fast) |
| **Google Gemini** | `OpenAICompatibleLLM` | Free quota |
| **Ollama** | `OpenAICompatibleLLM.forOllama()` | Local, free |
| Qwen | `QwenLLM` | — |
| Generic | `OpenAICompatibleLLM` | Any OpenAI-compatible |
| Mock | `MockLLM` | Testing |

## Strategy + Composite Pattern

All capability modules follow the same pattern:

```java
// Reasoning — composite fallback chain
Reasoner r = new DefaultReasoner(List.of(
    new ChainOfThought(llm),
    new TreeOfThought(llm)));

// Evaluation — weighted judge chain  
Evaluator e = CompositeEvaluator.defaults(new LLMJudge(llm));

// Perception — multi-sensor fusion
Perceptor p = new CompositePerceptor<>(
    new CodebasePerceptor(),
    new FileSystemPerceptor(),
    new BrowserPerceptor());

// Planning — strategy with feedback
AdaptivePlanner planner = new AdaptivePlanner(llm);
planner.recordFeedback(feedback);
```

## Quality

```bash
mvn test                # 165 tests, 22 test files
mvn spotbugs:check      # bytecode bug detection (Medium+)
mvn pmd:check           # static analysis (bestpractices + errorprone)
mvn checkstyle:check    # Sun coding standards
mvn test jacoco:report  # coverage report → target/site/jacoco/
```

## CLI

```bash
mvn exec:java -Dexec.mainClass="io.sketch.mochaagents.cli.CliBootstrap"

> What is 2 + 2?
[Agent] Processing: "What is 2 + 2?"
[Agent] Result: 4

> status
Agent 'repl-agent' ready. 4 tools loaded.
```

## Project Stats

| Metric | Value |
|--------|-------|
| Source files | 228 (core) |
| Test files | 22 |
| Tests | 165 |
| Build | PASS |
| Stubs | 0 |
| Unimplemented interfaces | 0 |

## License

Apache License 2.0
