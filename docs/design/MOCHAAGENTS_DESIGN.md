# MochaAgents 架构设计文档

## 1. 项目概述

MochaAgents 是一个轻量级 AI Agent 框架，采用 Java 17 实现，融合**面向对象（OOP）**与**函数式编程（FP）**两种范式，提供完整的智能体构建、规划、编排、推理、学习与监控能力。

### 1.1 技术栈

| 组件 | 技术选型 | 版本 |
|------|---------|------|
| 语言 | Java | 17 |
| 构建 | Maven | 3.x |
| HTTP 客户端 | OkHttp | 4.12.0 |
| JSON 序列化 | Jackson | 2.16.1 |
| 动态编译 | Janino | 3.1.12 |
| 响应式流 | Reactor Core | 3.6.2 |
| Python 执行 | Jython Standalone | 2.7.3 |
| 日志 | SLF4J + Logback | 2.0.9 / 1.4.11 |
| 测试 | JUnit 5 | 5.10.0 |

### 1.2 设计范式

```
┌──────────────────────────────────────────────────┐
│                 MochaAgents                       │
│                                                   │
│  ┌──────────────┐         ┌──────────────────┐   │
│  │   OOP Track   │         │   FP Track       │   │
│  │               │         │                  │   │
│  │ • 抽象基类    │         │ • @Functional    │   │
│  │ • Builder模式 │         │   Interface      │   │
│  │ • Template    │  协同    │ • Lambda注入     │   │
│  │   Method      │ ←────→  │ • 链式组合       │   │
│  │ • 不可变Record│         │ • 策略模式       │   │
│  └──────────────┘         └──────────────────┘   │
│                                                   │
└──────────────────────────────────────────────────┘
```

---

## 2. 整体架构

### 2.1 包结构

```
src/main/java/io/sketch/mochaagents/
├── core/            # 核心抽象层（Agent 接口、状态机、生命周期、自主循环）
├── context/         # 上下文管理（上下文窗口、压缩策略）
├── perception/      # 感知层（多源感知器、感知融合）
├── reasoning/       # 推理层（推理链、CoT/ToT/PoT/GoT 策略）
├── plan/            # 规划系统（计划生成、任务分解、依赖图）
├── execution/       # 执行引擎（同步/并行/沙箱执行器、多语言运行时）
├── tool/            # 工具系统（工具注册、执行器、工具管线、MCP 客户端）
├── memory/          # 记忆系统（工作记忆、情景记忆、语义记忆、记忆巩固）
├── learn/           # 学习层（经验积累、FewShot/Feedback/Curriculum 策略）
├── interaction/     # 交互层（交互模式、权限管理）
├── orchestration/   # 编排系统（Agent 团队、消息总线、协商协议）
├── llm/             # LLM 集成（多提供商、路由、降级、成本优化）
├── safety/          # 安全层（内容过滤、代码校验、沙箱执行、审计追踪）
├── evaluation/      # 评估系统（质量/安全/性能指标、幻觉检测）
├── monitor/         # 监控系统（遥测、指标、追踪、仪表盘）
└── examples/        # 示例 Agent（编码/审查/DevOps/全栈）
```

### 2.2 模块依赖关系

```
                   ┌─────────────┐
                   │    core     │  ← 所有模块的基础抽象
                   └──────┬──────┘
          ┌────────┬──────┼──────┬────────┬────────┐
          ▼        ▼      ▼      ▼        ▼        ▼
     context  perception reasoning plan  execution  tool
          │        │      │      │        │        │
          └────────┴──────┴──────┴────────┴────────┘
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
       memory           learn          interaction
          │                │                │
          └────────────────┼────────────────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
        orchestration     llm        safety
              │            │            │
              └────────────┼────────────┘
                           │
              ┌────────────┼────────────┐
              ▼                         ▼
         evaluation                 monitor
```

---

## 3. 模块详细设计

### 3.1 核心层 (core/)

**职责**: 提供 Agent 统一抽象、状态管理、元数据与生命周期控制。

#### 核心接口

| 接口/类 | 类型 | 说明 |
|---------|------|------|
| `Agent<I,O>` | interface | 统一 Agent 抽象，含同步/异步执行及 6 种函数式组合方法 |
| `AgentContext` | record | 会话上下文（sessionId, userId, userMessage, metadata） |
| `AgentState` | enum | 生命周期状态机：IDLE → INITIALIZING → RUNNING → COMPLETED/FAILED/CANCELLED |
| `AgentMetadata` | record | 元数据（name, version, description, capabilities, modelInfo） |
| `AgentListener<I,O>` | interface | 事件监听器（onStart/onComplete/onError/onProgress） |
| `AgentEvent<T>` | record | 事件载体 |
| `BaseAgent<I,O>` | abstract class | 基础实现，采用 Template Method 模式 |
| `CompositeAgent<I,O>` | class | OOP 组合代理 |
| `FunctionalAgent<I,O>` | class | 从 `Function<I,O>` 创建的轻量 Agent |
| `AsyncFunctionalAgent<I,O>` | class | 从异步函数创建的 Agent |

#### Agent 函数式组合

Agent 接口通过 `default` 方法提供 6 种组合能力：

```
Agent<I,O>
  ├── before(Function<T,I>)          → Agent<T,O>     // 前置类型映射
  ├── after(Function<O,T>)           → Agent<I,T>     // 后置类型映射
  ├── andThen(Agent<O,T>)            → Agent<I,T>     // 链式组合
  ├── when(Predicate<I>, Agent)      → Agent<I,O>     // 条件执行
  ├── withRetry(int)                 → Agent<I,O>     // 重试机制
  ├── withTimeout(long)              → Agent<I,O>     // 超时控制
  └── of(Function) / ofAsync(...)    → Agent<I,O>     // 工厂方法
```

#### 自主循环 (core/loop/)

```
AgenticLoop<I,O>              # 自主循环接口
  ├── ReActLoop               # Reasoning-Action-Observation
  ├── ObservePlanActReflect   # OPAR (Observe-Plan-Act-Reflect)
  └── ThinkActObserve         # TAO (Think-Act-Observe)

TerminationCondition          # 终止条件（最大步骤、满足条件、超时）
StepResult                    # 单步执行结果
LoopState                     # 循环状态

ReflectionEngine              # 反思引擎（@FunctionalInterface）
  ├── SelfCritique            # 自我批评
  └── ImprovementPlan         # 改进计划
```

**循环执行流程**:

```
┌──────────┐    step    ┌───────────┐
│  Agent   │ ─────────→ │ Agentic   │
│ (业务)    │ ←───────── │  Loop     │
└──────────┘   result   │ (策略)    │
                         └───────────┘
                               │
                    ┌──────────┴──────────┐
                    ▼                     ▼
            TerminationCondition   ReflectionEngine
             (是否终止?)              (反思+改进)
```

---

### 3.2 上下文管理 (context/)

**职责**: 管理 LLM 上下文窗口，防止 Token 溢出。

```
ContextChunk              # 上下文块（内容 + Token计数）
ContextWindow             # 上下文窗口（容量管理，LRU淘汰）
ContextManager            # 上下文管理器（统一入口）
ContextCompressor         # 压缩器接口
ContextStrategy           # 策略接口

策略实现:
├── SlidingWindowStrategy # 滑动窗口
├── SummarizationStrategy # 摘要压缩
└── HybridContextStrategy  # 混合策略（窗口+摘要）
```

---

### 3.3 感知层 (perception/)

**职责**: 感知多源输入，支持过滤与融合。

```
Perceptor<I,O>            # 感知器接口 + filter() 组合
PerceptionResult<T>       # 感知结果

处理器:
├── BrowserPerceptor       # 浏览器内容感知
├── CodebasePerceptor      # 代码库感知
├── FileSystemPerceptor    # 文件系统感知
└── TerminalPerceptor      # 终端输出感知

融合:
└── ContextFuser           # 多源上下文融合器

基础设施:
├── Environment            # 感知环境
└── Observation            # 观察数据
```

---

### 3.4 推理层 (reasoning/)

**职责**: 提供多策略推理能力。

```
Reasoner                  # 推理器接口（可插拔策略）
ReasoningChain            # 推理链
ReasoningStep             # 推理步骤
ReasoningStrategy         # 策略接口

推理策略:
├── ChainOfThought         # 链式思维 (CoT)
├── TreeOfThought          # 树状思维 (ToT)
├── ProgramOfThought       # 程序化思维 (PoT)
└── GraphOfThought         # 图状思维 (GoT)

验证器:
├── LogicVerifier          # 逻辑验证
├── ConsistencyChecker     # 一致性检查
└── ReasoningVerifier      # 推理验证器
```

---

### 3.5 规划系统 (plan/)

**职责**: 任务分解与执行计划管理。

```
Planner<T>                # 规划器接口
  ├── generatePlan(PlanningRequest)
  ├── replan(Plan, ExecutionFeedback)
  └── 策略: PlanningStrategy

Plan<T>                   # 计划接口
  ├── getSteps() / getCurrentStep() / advance()
  ├── getDependencyGraph()
  ├── validate() / serialize()
  └── 状态: PlanStatus (PENDING/IN_PROGRESS/COMPLETED/FAILED)

PlanStep                  # 计划步骤
  ├── id, name, type (TASK/DECISION/CHECKPOINT/LOOP)
  ├── status (PENDING/RUNNING/COMPLETED/FAILED/SKIPPED)
  ├── inputs[], dependencies[], timeout
  └── execute()

DependencyGraph           # 依赖图（addDependency, getDependencies, topological sort）

规划器实现:
├── DynamicPlanner         # 动态规划器
├── strategy/DefaultPlan   # 默认计划实现
├── strategy/HierarchicalPlanner  # 层级规划器
├── strategy/AdaptivePlanner      # 自适应规划器
├── strategy/ReplanningStrategy   # 重规划策略
├── decomposer/TaskDecomposer     # 任务分解器
└── decomposer/SemanticDecomposer # 语义分解器
```

---

### 3.6 执行引擎 (execution/)

**职责**: 计划步骤的实际执行，支持多种执行模式与运行时环境。

```
Executor                  # 执行器接口
  ├── executeStep(PlanStep)
  ├── executePlan(Plan)
  └── executeWithCallback(Plan, Callback)

引擎实现:
├── DefaultExecutor        # 顺序执行
├── ParallelExecutor       # 并行执行
└── SandboxExecutor        # 沙箱安全执行

执行数据:
├── ExecutionResult        # 执行结果
├── ExecutionReport        # 执行报告
├── ExecutionContext       # 执行上下文
├── StepExecution          # 步骤执行记录
└── ExecutionCallback      # 执行回调 (@FunctionalInterface)

运行时环境:
├── RuntimeEnvironment     # 抽象运行时
├── PythonRuntime          # Python (Jython)
├── NodeRuntime            # Node.js
└── ShellRuntime           # Shell
```

---

### 3.7 工具系统 (tool/)

**职责**: Tool 抽象、注册、执行与管线编排。

```
Tool                      # 工具接口
  ├── getName() / getDescription() / getInputs()
  ├── call(Map<String,Object>) → Object
  └── getSecurityLevel() → {LOW, MEDIUM, HIGH, CRITICAL}

ToolRegistry              # 工具注册表 (ConcurrentHashMap)

ToolExecutor              # 工具执行器
  ├── execute(tool, args) → ToolResult
  └── executeBatch(batch) → List<ToolResult>

工具分类:
├── BrowserTool            # 浏览器操作
├── CodeExecutionTool      # 代码执行
├── FileSystemTool         # 文件系统
├── GitTool                # Git 操作
├── SearchTool             # 搜索
└── TerminalTool           # 终端命令

工具编排:
├── Workbench              # 工作台接口
├── ToolPipeline           # 工具管线 (链式执行)
└── ToolOrchestrator       # 工具编排器 (前后钩子)

MCP:
└── McpClient              # MCP 协议客户端接口
```

---

### 3.8 记忆系统 (memory/)

**职责**: 多类型记忆的存储、检索与巩固。

```
Memory                    # 记忆接口
  ├── id, content, type (WORKING/EPISODIC/SEMANTIC)
  ├── createdAt, lastAccessedAt, accessCount
  ├── importance (0.0-1.0), tags, metadata
  └── touch()

MemoryType                # 枚举: WORKING, EPISODIC, SEMANTIC

MemoryManager             # 记忆管理器
  ├── store(memory) / retrieve(id)
  ├── query(tags, type, minImportance)
  ├── consolidate() / forget(id)
  └── 合并/排序/限额管理

实现:
├── WorkingMemory          # 工作记忆（容量限制, LRU淘汰）
├── EpisodicMemory         # 情景记忆（按时间+重要性检索）
└── SemanticMemory         # 语义记忆（按相关性检索）

巩固:
├── MemoryConsolidator     # 记忆巩固器
├── ImportanceScorer       # 重要性评分
└── MemoryCompressor       # 记忆压缩

检索:
├── ContextualRetriever    # 上下文检索
└── HybridRetriever        # 混合检索（语义+上下文）
```

**记忆生命周期**:

```
创建 ──→ 存储 ──→ 访问(touch) ──→ 评分更新
  │                                   │
  └──←── 遗忘(低重要性) ←── 巩固 ←───┘
```

---

### 3.9 学习层 (learn/)

**职责**: Agent 从经验中学习和改进行为。

```
Learner<I,O>              # 学习器接口
  ├── learn(Experience) / learnBatch(List)
  ├── infer(I) → O
  └── getStrategy() / setStrategy()

Experience<I,O>           # 经验记录
  ├── id, input, output, reward (0.0-1.0)
  ├── timestamp, context, tags
  └── withReward(double) / rate() / record()

LearningStrategy          # 学习策略接口
  ├── onExperience(exp)      # 处理单条经验
  └── buildPrompt(examples)  # 构建 FewShot prompt

策略实现:
├── FewShotLearner          # FewShot 学习
├── FeedbackLearner         # 反馈学习
└── CurriculumLearner       # 课程学习

经验管理:
├── ExperienceBuffer        # 经验缓冲区（带重要性采样）
└── ExperienceReplay        # 经验回放（批量抽样）
```

---

### 3.10 交互层 (interaction/)

**职责**: Agent 与外部世界的交互通道与权限控制。

```
Interactor                # 交互器接口
  ├── send(msg) / receive() / ask(prompt)
  ├── confirm(prompt) → boolean
  ├── showProgress(msg, %) / showError(msg)
  └── getMode() / setMode()

InteractionMode           # 交互模式接口
  ├── AutonomousMode       # 自主模式
  ├── CollaborativeMode    # 协作模式
  └── SupervisedMode       # 监督模式

权限系统:
├── Permission             # 权限接口 (check + grant + deny)
├── PermissionManager      # 权限管理器
├── PermissionRequest      # 权限请求
└── PermissionLevel        # 权限级别
```

---

### 3.11 编排系统 (orchestration/)

**职责**: 多 Agent 协调、任务分派与结果聚合。

```
Orchestrator              # 编排器接口
  ├── register(Agent, Role) / unregister(agentId)
  ├── orchestrate(input, strategy) → O
  └── getTeam() / shutdown()

AgentTeam                 # Agent 团队
  ├── members, roles
  ├── addMember / removeMember
  └── find(role) / assign(task)

Role                      # 角色定义（roleType, permissions, priority）
RoleType                  # 枚举: LEADER, WORKER, REVIEWER, EXECUTOR, OBSERVER

OrchestrationStrategy     # 编排策略（泛型 execute 方法）

策略实现:
├── SupervisorAgent        # 监督者模式
├── SwarmStrategy          # 蜂群策略
└── DebateStrategy         # 辩论策略

通信:
├── MessageBus             # 消息总线（pub/sub + topic routing）
├── AgentMessage           # Agent 消息
└── NegotiationProtocol    # 协商协议（propose/counter/accept/reject）
```

**编排流程**:

```
输入 ──→ Orchestrator ──→ AgentTeam
              │                │
              │           ┌────┴────┐
              ▼           ▼         ▼
        Orchestration  Role1     Role2 ...
          Strategy        │
              │           ▼
              ▼        Agent.execute()
          结果 ←── 聚合结果
```

---

### 3.12 LLM 集成 (llm/)

**职责**: 大语言模型调用的统一抽象与路由。

```
LLM                       # LLM 接口
  ├── complete(LLMRequest) → LLMResponse
  ├── completeAsync → CompletableFuture
  └── stream → StreamingResponse

LLMRequest                # 请求体 (messages, model, temperature, maxTokens, tools)
LLMResponse               # 响应体 (content, tokens, model, finishReason)
StreamingResponse         # 流式响应 (Iterator<Delta> + onToken callback)

提供商:
├── OpenAILLM              # OpenAI 兼容API
├── AnthropicLLM           # Anthropic API
└── LocalLLM               # 本地模型

路由:
├── LLMRouter              # 路由器（多模型分发）
├── CostOptimizer          # 成本优化器
└── FallbackStrategy       # 降级策略
```

---

### 3.13 安全层 (safety/)

**职责**: 内容过滤、代码校验、沙箱执行与审计追踪。

```
SafetyManager             # 安全管理器（统一入口）
  ├── checkContent(content)    → boolean
  ├── validateCode(code)       → boolean
  ├── safeExecute(code, lang)  → String
  └── addBlockedPattern(p)

内容过滤:
├── ContentFilter          # 内容安全过滤器
└── 屏蔽模式列表            # 自定义屏蔽规则

代码安全:
├── CodeValidator          # 代码校验器
└── Sandbox                # 沙箱执行

策略:
├── SafetyPolicy           # 安全策略接口
└── PolicyEnforcer         # 策略执行器

审计:
├── AuditLogger            # 审计日志
└── ExecutionTracer        # 执行追踪器
```

---

### 3.14 评估系统 (evaluation/)

**职责**: Agent 输出的质量、安全与性能评估。

```
Evaluator                 # 评估器接口
  ├── evaluate(input, output, expected) → EvaluationResult
  └── getCriteria() → EvaluationCriteria

EvaluationResult          # 评估结果 (score, pass, metrics, feedback)
EvaluationCriteria        # 评估标准 (name, weight, threshold)

评判器:
├── LLMJudge               # LLM 评判
├── HumanJudge             # 人工评判
└── AutomatedJudge         # 自动评判（规则匹配）

指标:
├── QualityMetrics         # 质量指标（准确性、完整性、相关性）
├── SafetyMetrics          # 安全指标（毒性、偏见、合规）
├── PerformanceMetrics     # 性能指标（延迟、吞吐、Token消耗）
└── HallucinationDetector  # 幻觉检测器
```

---

### 3.15 监控系统 (monitor/)

**职责**: Agent 运行时可观测性数据收集与可视化。

```
Telemetry                 # 遥测接口
  ├── trackEvent(name, properties)
  ├── trackMetric(name, value)
  ├── trackException(throwable)
  └── startOperation / endOperation

MetricsCollector          # 指标收集器
  ├── increment / decrement counter
  ├── recordGauge / recordHistogram
  └── getCounters / getGauges / getHistograms

Tracer                    # 分布式追踪
  ├── startSpan / endSpan / addEvent
  └── getSpans / getRootSpan / getCurrentSpan

TraceSpan                 # 追踪跨域接口 (id, name, startTime, endTime, duration, status, depth)

仪表盘:
├── AgentDashboard         # Agent 运行仪表盘
└── ExecutionViewer        # 执行可视化查看器
```

---

### 3.16 示例 Agent (examples/)

**职责**: 展示框架用法的参考实现。

```
CodingAgent               # 编码 Agent（Builder 模式）
CodeReviewAgent           # 代码审查 Agent
DevOpsAgent               # DevOps Agent
FullStackAgent            # 全栈 Agent
```

所有示例 Agent 均继承 `BaseAgent<String, String>`，使用嵌套 `Builder` 类遵循框架约定。

**示例 - CodingAgent 定义**:

```java
public class CodingAgent extends BaseAgent<String, String> {
    protected CodingAgent(Builder builder) { super(builder); }

    @Override
    protected String doExecute(String input) {
        // 核心编码逻辑
        return "Generated code for: " + input;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder extends BaseAgent.Builder<String, String, Builder> {
        @Override
        public CodingAgent build() { return new CodingAgent(this); }
    }
}
```

---

## 4. 设计模式索引

| 设计模式 | 应用位置 | 说明 |
|---------|---------|------|
| **Template Method** | `BaseAgent.execute()` → `doExecute()` | 定义执行骨架，子类只需实现核心逻辑 |
| **Builder** | `AgentMetadata`, `AgentContext`, `BaseAgent.Builder<T>` | 自引用泛型 `T extends Builder` 支持链式调用 |
| **Strategy** | `ReasoningStrategy`, `PlanningStrategy`, `ContextStrategy`, `LearningStrategy` | 算法族可插拔，运行时切换 |
| **Decorator** | `Agent.andThen()`, `Agent.before()`, `Agent.after()` | 函数式组合增强 Agent 能力 |
| **Composite** | `CompositeAgent` | 多个 Agent 组合为一个统一 Agent |
| **Registry** | `ToolRegistry`, Agent 注册体系 | 中心化工具/Agent 发现 |
| **Observer** | `AgentListener+I,O>` | Agent 执行事件监听 |
| **Chain of Responsibility** | `ToolPipeline`, `ReasoningChain` | 管道式处理 |
| **Mediator** | `MessageBus`, `Orchestrator` | Agent 间通信协调 |
| **Factory Method** | `Agent.of()`, `Agent.ofAsync()` | 从函数创建 Agent |
| **Repository** | `MemoryManager`, `ExperienceBuffer` | 记忆/经验存储与检索 |

---

## 5. Agent 中组合各能力

Context 作为显式参数贯穿所有调用链，各模块读写同一个 Context 实例，形成数据闭环。

### 5.1 核心调用约定

```java
// Agent 接收 Context 参数
O execute(I input, ContextManager ctx);

// AgenticLoop 驱动循环，ctx 在每轮中传递
O run(Agent<I,O> agent, I input, TerminationCondition cond, ContextManager ctx);

// 各能力模块均接受 ctx
PerceptionResult<O> perceive(I input, ContextManager ctx);
ReasoningChain     reason(String question, ContextManager ctx);
Plan<T>            generatePlan(PlanningRequest<T> req, ContextManager ctx);
Object             call(Map<String,Object> args, ContextManager ctx);
boolean            checkContent(String content, ContextManager ctx);
EvaluationResult   evaluate(String in, String out, String expected, ContextManager ctx);
```

### 5.2 使用 CapableAgent — 一行 Builder 完成组装

`CapableAgent` 是内置全流水线的抽象基类，通过 Builder 注入所有能力：

```java
// 一行链式调用，所有能力注入完成
CapableAgent<String, String> agent = MyCapableAgent.builder()
    .name("coding-agent")
    .perceptor(new CodebasePerceptor())
    .reasoner(reasoner)
    .planner(new DynamicPlanner())
    .toolRegistry(tools)
    .safetyManager(safety)
    .evaluator(new LLMJudge(llm))
    .memoryManager(memory)
    .learner(new FewShotLearner())
    .reflectionEngine(ReflectionEngine.noop())
    .build();

// Context 作为显式参数传入，自动编排 8 步流水线
ContextManager ctx = new ContextManager(8192, new SlidingWindowStrategy(), null);
String result = agent.execute("修复登录模块 NPE 异常", ctx);
```

`execute(input, ctx)` 内部自动执行：注入记忆 → 感知 → 推理 → 规划 → 安全校验 → 工具调用 → Agent 逻辑 → 评估 → 反思 → 学习 → 压缩.

### 5.3 只覆写领域逻辑

子类只需覆写 `doExecute`，流水线由基类托管：

```java
public class MyCapableAgent extends CapableAgent<String, String> {
    protected MyCapableAgent(Builder b) { super(b); }

    @Override
    protected String doExecute(String input, ContextManager ctx) {
        // 从 ctx 读取感知/推理/计划结果，专注领域决策
        List<ContextChunk> context = ctx.getContext();
        return llm.complete(buildPrompt(input, context)).content();
    }

    public static Builder builder() { return new Builder(); }
    public static class Builder extends CapableAgent.Builder<String, String, Builder> {
        @Override public MyCapableAgent build() { return new MyCapableAgent(this); }
    }
}
```

### 5.4 按需覆写流水线步骤

CapableAgent 的每个流水线步骤都是独立的 `protected` 方法，可选择性覆写：

```java
// 自定义安全校验：高风险操作需用户确认
@Override
protected boolean safetyCheck(PlanStep step, ContextManager ctx) {
    if (step.type() == PlanStep.StepType.ACTION 
            && step.description().contains("delete")) {
        return interactor.confirm("确认删除? " + step.description());
    }
    return super.safetyCheck(step, ctx);
}

// 自定义评估：跳过小任务
@Override
protected EvaluationResult evaluate(I input, O output, ContextManager ctx) {
    if (input != null && input.toString().length() < 50) return null;
    return super.evaluate(input, output, ctx);
}
```

编排时每个 Agent 共享同一个 ContextManager，通过 MessageBus 同步各自的中间结果。

```java
ContextManager ctx = new ContextManager(16384, new HybridContextStrategy(), null);
Orchestrator orch = new DefaultOrchestrator(ctx);

// 注册团队
AgentTeam team = new AgentTeam()
    .addMember(codingAgent, new Role(RoleType.WORKER, "coder"))
    .addMember(reviewAgent, new Role(RoleType.REVIEWER, "reviewer"))
    .addMember(testAgent,  new Role(RoleType.EXECUTOR, "tester"));

for (AgentTeam.Member m : team.members()) {
    orch.register(m.agent(), m.role(), ctx);
}

// 启动编排 — ctx 在 Agent 间共享
String result = orch.orchestrate("实现用户登录模块", 
    OrchestrationStrategy.sequential(), ctx);
```

### 5.5 记忆注入

Agent 每次执行前从 MemoryManager 检索相关历史，注入 Context，使 Agent 感知跨会话上下文。

```java
MemoryManager memory = new MemoryManager();
memory.store(new EpisodicMemory("上次修复了 NPE，根因是未初始化", 0.8));

// Agent 执行前注入记忆
List<Memory> relevant = memory.query(Set.of("npe", "bug"), MemoryType.EPISODIC, 0.5);
for (Memory m : relevant) {
    ctx.addChunk(ContextChunk.of("memory", m.content()));
}

// 执行后存储新经验
String result = agent.execute(input, ctx);
memory.store(new EpisodicMemory("修复 " + input, 0.7));

// 定期巩固，清理低重要性记忆
memory.consolidate();
```

### 5.6 学习反馈闭环

每轮执行后 Learner 记录经验，下次执行时 FewShot 示例自动注入 Context。

```java
Learner<String, String> learner = new FewShotLearner();

// 执行并学习
String output = agent.execute(input, ctx);
Experience<String, String> exp = Experience.record(input, output, evaluator, ctx);
learner.learn(exp);

// 下次执行前注入 FewShot 示例
String fewShotPrompt = learner.getStrategy().buildPrompt(learner.topExperiences(3));
ctx.addChunk(ContextChunk.of("fewshot", fewShotPrompt));
```

---

## 6. 数据流总览

```
用户输入
  │
  ▼
┌──────────────────────────────────────────────────────────┐
│              ContextManager (显式参数传递)                 │
│                                                          │
│  Memory ──→ ctx.addChunk(memory)                         │
│  Perceptor.perceive(input, ctx) ──→ ctx.addChunk(result) │
│  Reasoner.reason(q, ctx) ──→ ctx.addChunk(chain)        │
│  Planner.generatePlan(req, ctx) ──→ ctx.addChunk(plan)  │
│  Safety.checkContent(action, ctx) ──→ 放行/阻断          │
│  Tool.call(args, ctx) ──→ ctx.addChunk(toolResult)      │
│  Agent.execute(input, ctx) ──→ output                   │
│  Evaluator.evaluate(in, out, expected, ctx) ──→ eval    │
│  ReflectionEngine.reflect(result, critique) ──→ 改进    │
│  Learner.learn(experience) ──→ FewShot 注入下次 ctx     │
│  ctx.compress() ──→ 防止溢出                             │
└──────────────────────────────────────────────────────────┘
  │
  ▼
最终输出
```

---

## 7. 编译与运行

### 编译

```bash
mvn compile
```

### 运行测试

```bash
mvn test
```

### 项目统计

| 指标 | 数值 |
|------|------|
| 包数量 | 16 |
| Java 源文件 | 169 |
| Java 版本 | 17 |
| 构建工具 | Maven |
| 外部依赖 | 8 |

### 依赖清单

| 依赖 | 版本 | 用途 |
|------|------|------|
| OkHttp | 4.12.0 | HTTP 客户端，用于 LLM API 调用 |
| Jackson | 2.16.1 | JSON 序列化/反序列化 |
| Janino | 3.1.12 | 运行时 Java 代码编译 |
| Reactor Core | 3.6.2 | 响应式流处理 |
| Jython Standalone | 2.7.3 | Python 代码执行引擎 |
| SLF4J API | 2.0.9 | 日志门面 |
| Logback Classic | 1.4.11 | 日志实现 |
| JUnit 5 | 5.10.0 | 单元测试框架 |

---

## 8. 扩展

### 8.1 实现自定义 Agent（注入 Context）

继承 `BaseAgent<I,O>`，覆写 `doExecute(I input, ContextManager ctx)`：

```java
public class MyAgent extends BaseAgent<String, String> {
    protected MyAgent(Builder b) { super(b); }

    @Override
    protected String doExecute(String input, ContextManager ctx) {
        // 从 ctx 读取感知/推理/计划结果
        List<ContextChunk> chunks = ctx.getContext();
        // 执行决策逻辑
        return process(input, chunks);
    }

    public static Builder builder() { return new Builder(); }
    public static class Builder extends BaseAgent.Builder<String, String, Builder> {
        @Override public MyAgent build() { return new MyAgent(this); }
    }
}
```

### 8.2 添加自定义 Tool（接收 Context）

```java
public class MyTool implements Tool {
    @Override public String getName() { return "my-tool"; }
    @Override public String getDescription() { return "..."; }
    @Override public Map<String, ToolInput> getInputs() { return Map.of(); }
    @Override public String getOutputType() { return "string"; }
    @Override public Object call(Map<String, Object> args, ContextManager ctx) {
        // 可读写 ctx 获取上下文信息
        return doSomething(args, ctx);
    }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
}
```

### 8.3 添加 LLM 提供商

```java
public class MyLLMProvider implements LLM {
    @Override public LLMResponse complete(LLMRequest request) { /* ... */ }
    @Override public CompletableFuture<LLMResponse> completeAsync(LLMRequest r) { /* ... */ }
    @Override public StreamingResponse stream(LLMRequest request) { /* ... */ }
    @Override public String modelName() { return "my-model"; }
    @Override public int maxContextTokens() { return 8192; }
}
```