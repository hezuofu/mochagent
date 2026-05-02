# MochaAgents

A Java-based AI agent framework — a pure Java reimplementation of [smolagents](https://github.com/huggingface/smolagents), designed for production-grade multi-agent systems.

## Architecture

```
Agent<I,O>                    # Unified agent interface (sync / async / composable)
├── CapableAgent              # Agent with tools, memory, and LLM integration
│   ├── MultiStepAgent        # ReAct loop base (Think → Act → Observe)
│   │   ├── ToolCallingAgent  # Tool-calling via structured output or JSON
│   │   └── CodeAgent         # Code-execution agent with sandboxed runtime
│   └── CompositeAgent        # Composite pattern — chain multiple agents
```

## Features

### Agent Layer
- **Unified `Agent<I,O>` interface** — sync/async execution, functional composition (`before`, `after`, `condition`)
- **ReAct loop engine** — multi-step reasoning with Think-Act-Observe, spanning `SystemPromptStep → TaskStep → PlanningStep → ActionStep → FinalAnswerStep`
- **Three loop strategies**: `ReAct`, `ThinkActObserve`, `ObservePlanActReflect`
- **Termination control** — configurable max steps, `final_answer()` barrier, and custom conditions
- **Reflection** — built-in self-critique and improvement plan generation

### LLM Integration
- **Provider-agnostic `LLM` interface** — sync, async, and streaming
- **7 providers**: OpenAI, Anthropic Claude, DeepSeek, Qwen (通义千问), Ollama-compatible local models, generic OpenAI-compatible API, Mock (testing)
- **LLM Router** — fallback strategy and cost optimization
- **`BaseApiLLM`** — shared OkHttp + Jackson transport layer for custom providers

### Tools & Execution
- **`Tool` interface** — name, description, typed inputs, security level (LOW/MEDIUM/HIGH/CRITICAL)
- **6 built-in tool categories**: Browser, CodeExecution, FileSystem, Git, Search, Terminal
- **Tool pipeline & workbench** — chain and orchestrate tools
- **MCP (Model Context Protocol) client**
- **Janino runtime compiler** — compile and execute generated Java code at runtime
- **Jython integration** — execute Python code blocks within the sandbox

### Memory & Context
- **`AgentMemory`** — step-level execution trace with planning, action, and observation entries
- **Pluggable stores**: `InMemoryMemoryStore`, `MarkdownMemoryStore`
- **`MemoryManager`** — retrieval-augmented memory with metadata filtering
- **Context window management** — sliding window, summarization, and hybrid strategies

### Planning
- **`Planner` + `PlanningStrategy`** — hierarchical, adaptive, and replanning strategies
- **Task decomposition** — semantic decomposition via `SemanticDecomposer`
- **Dependency graph** — model step interdependencies
- **Validation** — plan verification before execution

### Reasoning
- **4 reasoning strategies**: Chain-of-Thought, Tree-of-Thought, Graph-of-Thought, Program-of-Thought
- **Verifiers**: consistency checker, logic verifier
- **Structured `ReasoningChain`** — traceable reasoning with intermediate steps

### Multi-Agent Orchestration
- **`AgentTeam`** — role-based agent teams with leader/worker/specialist roles
- **3 orchestration strategies**: Debate, Supervisor, Swarm
- **Negotiation protocol** — inter-agent messaging with structured `AgentMessage`
- **Message bus** — asynchronous message passing between agents

### Safety & Perception
- **`SafetyManager`** — multi-layered safety with configurable policies
- **Code validator** — static analysis of generated code
- **Content filter** — input/output content filtering
- **Sandbox** — isolated execution environment
- **Audit logging** — full execution trace with `ExecutionTracer`
- **Perception** — browser, codebase, filesystem, and terminal environment sensors

### Learning
- **`Learner<I,O>` interface** — agents that improve from experience
- **Three strategies**: Few-Shot, Curriculum, Feedback-based
- **Experience replay buffer** — store and replay execution histories

### Monitoring
- **`Tracer` + `TraceSpan`** — distributed tracing for agent execution
- **`MetricsCollector`** — collect and aggregate runtime metrics
- **Agent dashboard** — live execution viewer

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.9+

### Build

```bash
mvn clean compile
```

### Example: ToolCallingAgent

```java
import io.sketch.mochaagents.agent.impl.ToolCallingAgent;
import io.sketch.mochaagents.llm.provider.OpenAILLM;

var model = new OpenAILLM("gpt-4o", System.getenv("OPENAI_API_KEY"));

var agent = ToolCallingAgent.builder()
        .llm(model)
        .maxSteps(15)
        .build();

String answer = agent.run("What is the capital of France?");
```

### Example: CodeAgent

```java
import io.sketch.mochaagents.agent.impl.CodeAgent;
import io.sketch.mochaagents.llm.provider.OpenAILLM;

var model = new OpenAILLM("gpt-4o", System.getenv("OPENAI_API_KEY"));

var agent = CodeAgent.builder()
        .llm(model)
        .maxSteps(20)
        .build();

String answer = agent.run("Calculate the 20th Fibonacci number");
```

### Example: CompositeAgent

```java
import io.sketch.mochaagents.agent.impl.CompositeAgent;

var composite = new CompositeAgent<>(List.of(agent1, agent2, agent3));
List<String> results = composite.execute("Analyze this codebase");
// 或使用静态工厂: var composite = CompositeAgent.of(agent1, agent2, agent3);
```

## Module Overview

| Module | Package | Description |
|--------|---------|-------------|
| **agent** | `io.sketch.mochaagents.agent` | Core agent interfaces, ReAct loop, step types, builders |
| **llm** | `io.sketch.mochaagents.llm` | LLM abstraction, providers, request/response, router |
| **tool** | `io.sketch.mochaagents.tool` | Tool interface, registry, executor, pipeline, MCP client |
| **memory** | `io.sketch.mochaagents.memory` | Agent memory, storage backends, memory manager |
| **context** | `io.sketch.mochaagents.context` | Context window strategies, compression |
| **plan** | `io.sketch.mochaagents.plan` | Planner, task decomposition, dependency graphs |
| **reasoning** | `io.sketch.mochaagents.reasoning` | CoT/ToT/GoT/PoT strategies, verifiers |
| **orchestration** | `io.sketch.mochaagents.orchestration` | Agent teams, negotiation, supervisor, debate, swarm |
| **safety** | `io.sketch.mochaagents.safety` | Safety manager, code validator, content filter, sandbox, audit |
| **perception** | `io.sketch.mochaagents.perception` | Environment sensors and fusion |
| **learn** | `io.sketch.mochaagents.learn` | Learning strategies, experience replay |
| **interaction** | `io.sketch.mochaagents.interaction` | Autonomous, collaborative, supervised modes |
| **monitor** | `io.sketch.mochaagents.monitor` | Tracing, metrics, dashboard |
| **evaluation** | `io.sketch.mochaagents.evaluation` | Agent evaluation criteria and results |
| **prompt** | `io.sketch.mochaagents.prompt` | YAML-based prompt templates |

## Supported LLM Providers

| Provider | Class | Notes |
|----------|-------|-------|
| OpenAI | `OpenAILLM` | GPT-4o, GPT-4, GPT-3.5 |
| Anthropic | `AnthropicLLM` | Claude 3 Opus/Sonnet/Haiku |
| DeepSeek | `DeepSeekLLM` | DeepSeek-V2/V3, DeepSeek-R1 |
| Qwen | `QwenLLM` | Tongyi Qianwen (通义千问) |
| Local | `LocalLLM` | Ollama, LM Studio via `/v1` |
| Generic | `OpenAICompatibleLLM` | Any OpenAI-compatible endpoint |
| Mock | `MockLLM` | Deterministic responses for testing |

## License

Apache License