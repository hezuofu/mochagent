# MochaAgents 核心接口 OOP 架构设计

> 版本: 1.0  |  状态: 设计完成，待实施 Phase B

---

## 1. 架构总览

```
                      agent/Agent  (接口，2方法)
                           △
                           │ implements
                    agent/AbstractAgent  (抽象类，持有 Model/Tools)
                           △
                           │ extends
                   agent/AbstractReActAgent  (抽象类，Template Method)
                      △              △
                      │              │
              agent/CodeAgent   agent/ToolCallingAgent

   横向组合（HAS-A）:
   AbstractReActAgent
     ├── LanguageModel     (model/)
     ├── WorkingMemory     (memory/)
     ├── Metrics           (metrics/)
     ├── PlanningStrategy  (agent/)
     ├── HookRegistry      (hooks/)
     └── List<Tool>        (tools/)

   数据契约（Records）:
   spec/InvocationContext, spec/AgentResult, spec/CompletionRequest, spec/ExecutionResult

   独立模块:
   prompt/PromptTemplate   (提示模板接口)
   prompt/SystemReminder   (动态提醒 record)
   executor/CodeInterpreter (代码执行接口)
   mcp/McpClient            (MCP 客户端)
   tool/                    (权限、并发策略)
```

设计原则：
- **接口隔离**：Agent 只定义执行，身份/配置下沉到抽象类
- **Template Method**：ReAct 循环 `final` 固化，子类只填 `step()`
- **组合优先**：PlanningStrategy/HookRegistry 可插拔
- **函数式友好**：Strategy 用 SAM 接口，上下文用 immutable record

---

## 2. Agent 模块

### 2.1 Agent 接口 — `agent/Agent.java`

```java
public interface Agent {
    AgentResult execute(String task);
    AgentResult execute(String task, RunControls controls);
}
```

- 只定义"执行"契约。name/description/model/tools 是 AbstractAgent 的实现细节
- RunControls 从 agents/AgentRunControls 迁移（record）

### 2.2 AbstractAgent — `agent/AbstractAgent.java`

```java
public abstract class AbstractAgent implements Agent {
    protected final LanguageModel model;
    protected final List<Tool> tools;
    protected final String name;
    protected final String description;

    public LanguageModel model() { return model; }
    public List<Tool> tools() { return tools; }
    public String name() { return name; }
    public String description() { return description; }

    // Builder（泛型自引用，子类扩展）
    public abstract static class Builder<T extends Builder<T>> { ... }
}
```

- 持有所有 Agent 共享的字段
- Builder 模式，泛型自引用支持 `CodeAgent.Builder extends AbstractAgent.Builder<CodeAgent.Builder>`

### 2.3 AbstractReActAgent — `agent/AbstractReActAgent.java`

```java
public abstract class AbstractReActAgent extends AbstractAgent {
    protected final WorkingMemory memory;
    protected final Metrics metrics;
    protected final HookRegistry hooks;
    protected final PlanningStrategy planningStrategy;
    protected int maxSteps = 20;

    // Template Method — final，不可覆写
    @Override
    public final AgentResult execute(String task) { ... }
    @Override
    public final AgentResult execute(String task, RunControls controls) { ... }
    protected final AgentResult executeAgentLoop(String task, RunControls controls, ...) { ... }

    // 子类钩子
    protected abstract ActionStep step(int stepNumber);
    protected abstract String initializeSystemPrompt();

    // 可选覆写的钩子
    protected void onRunStart(...) {}
    protected boolean shouldPlanBeforeStep(int n) { return planningInterval != null && ...; }
    protected ChatMessage provideFinalAnswer(String task) { ... }
}
```

- `executeAgentLoop()` 是 `final`——ReAct 算法骨架，不可覆写
- 子类只实现 `step()` 和 `initializeSystemPrompt()`
- 搬迁自 agents/MultiStepAgent 的核心逻辑（primeConversation, executeAgentLoop, provideFinalAnswer）
- 类型迁移：Model → LanguageModel, AgentExecutionResult → AgentResult, Monitor → Metrics

### 2.4 PlanningStrategy — `agent/PlanningStrategy.java`

```java
@FunctionalInterface
public interface PlanningStrategy {
    PlanningStep plan(InvocationContext ctx);
}
```

- SAM 接口，可 lambda 注入：`agent.withPlanning(ctx -> ...)`
- 默认实现：SmolagentsPlanningStrategy（搬迁自 generatePlanningStepSmolagents）

### 2.5 CodeAgent — `agent/CodeAgent.java`

```java
public class CodeAgent extends AbstractReActAgent {
    private final CodeInterpreter interpreter;
    private final Set<String> authorizedImports;
    private final String[] codeBlockTags;

    @Override
    protected ActionStep step(int stepNumber) {
        // 1. model.generate → 解析代码块
        // 2. interpreter.execute(code)
        // 3. 返回 ActionStep
    }

    @Override
    public String initializeSystemPrompt() { ... }

    // 序列化
    public Map<String, Object> toDict() { ... }
    public void save(String path) { ... }
    public static CodeAgent fromFolder(String path, LanguageModel model) { ... }
    public void pushToHub(String repoId, String token) { ... }
}
```

### 2.6 ToolCallingAgent — `agent/ToolCallingAgent.java`

```java
public class ToolCallingAgent extends AbstractReActAgent {
    private final Map<String, Tool> resolver;

    @Override
    protected ActionStep step(int stepNumber) {
        // 1. model.generate(tools) → parse tool_calls
        // 2. 并行/顺序执行工具
        // 3. 返回 ActionStep
    }
}
```

---

## 3. 编排模块 (Orchestration)

编排职责已内置在 AbstractReActAgent 的 `executeAgentLoop()` 中：

```
while step <= maxSteps:
    if interruptRequested → return interrupted
    if shouldPlan → planningStrategy.plan(ctx) → memory.add
    actionStep = step(stepNumber)
    memory.add(actionStep)
    metrics.recordStep(actionStep)
    hooks.firePostStep(...)
    if error(nonRecoverable) → return error
    if finalAnswer → return success
    stepNumber++
return provideFinalAnswer()
```

InvocationContext 作为跨步骤的不可变快照，在每次循环中创建：

```java
InvocationContext ctx = new InvocationContext(
    task, memory.all(), systemPrompt, activeReminders, todoList, tokenUsage, stepNumber, state
);
```

---

## 4. 提示模块 (Prompt)

### 4.1 PromptTemplate — `prompt/PromptTemplate.java`

```java
public interface PromptTemplate {
    String name();
    String render(Map<String, Object> variables);
    String source();
}
```

- 模板名称如 "code_agent_system"、"planning_initial"
- `render(Map)` 使用 {{key}} 插值
- 默认实现：YamlPromptTemplate（从 YAML 文件读取）

### 4.2 SystemReminder — `prompt/SystemReminder.java`

```java
public record SystemReminder(String id, String content, Trigger trigger) {
    public enum Trigger { EVERY_STEP, TODO_EMPTY, TODO_CHANGED, TOOL_DENIED, MAX_STEPS_APPROACHING }
}
```

- Claude Code 风格的动态提示注入
- 编排循环中根据触发条件决定是否注入

---

## 5. Tool 模块

### 5.1 Tool 契约 — `tools/Tool.java`（保留现有）

```java
public interface Tool {
    String getName();
    String getDescription();
    Map<String, ToolInput> getInputs();
    String getOutputType();
    default void setup() {}
    Object call(Map<String, Object> arguments);
    String toCodePrompt();
    String toToolCallingPrompt();
    ToolDefinition toDefinition();
}
```

- 保持现有接口不变（Java 命名：getXxx）
- `api.Tool`（新风格命名：name/description/inputs/output）标记 @Deprecated，新代码可选使用

### 5.2 AbstractTool — `tools/AbstractTool.java`

- `call()` 实现 Template Method：setup → 参数校验 → `forward()` → 类型处理
- 子类只需实现 `forward(Map<String, Object> arguments)`
- 同时实现 `tools.Tool` 和 `api.Tool`（过渡期桥接）

### 5.3 ToolCollection — `tools/ToolCollection.java`

```java
public class ToolCollection implements AutoCloseable {
    public ToolCollection(List<Tool> tools) { ... }
    public ToolCollection(List<Tool> tools, AutoCloseable lifecycle) { ... }

    public static ToolCollection fromMcp(McpServerParameters params) { ... }
    public List<Tool> getTools() { ... }
    public Map<String, Tool> toMap() { ... }
}
```

- 工具集合，支持 MCP 加载
- `lifecycle` 参数用于关联 MCP 进程生命周期

### 5.4 ManagedAgentTool — `tools/ManagedAgentTool.java`

- 将 Agent 包装为 Tool（smolagents managed-agent 模式）
- 迁移后使用 `agent/Agent` 接口替代 `agents/MultiStepAgent`

### 5.5 工具权限与并发 — `tool/` 包

| 类型 | 说明 |
|------|------|
| `ToolPermission` | 5 级权限枚举（READ_ONLY → ALLOW） |
| `PermissionPolicy` | 授权接口：`authorize(Tool, args, userLevel) → boolean` |
| `ToolExecutionStrategy` | 执行策略：SEQUENTIAL / PARALLEL_SAFE / SPECULATIVE |

---

## 6. Skill 模块（待 Phase B 实现）

Skill 定义为知识文档，而非可执行代码：

```java
package io.sketch.mochaagents.skill;

public record Skill(
    String name,
    String description,
    List<String> steps,           // 步骤指南
    List<String> triggers,        // 触发条件
    List<String> suggestedTools,  // 建议工具
    String body                   // 正文
) {
    public String renderAsPrompt() {
        // 渲染为 Prompt 注入文本
    }
}

public interface SkillLoader {
    Skill load(String path);
}
```

- Skill 不继承 Tool，不继承 Agent
- 通过 `SkillLoader` 加载 → `renderAsPrompt()` 渲染 → 注入到 SystemPrompt
- Skill 是提示的一部分，而非执行的一部分

---

## 7. MCP 模块 — `mcp/McpClient.java`

```java
public final class McpClient implements AutoCloseable {
    public static McpClient connect(McpServerParameters params) { ... }
    public List<Tool> listTools() { ... }
    public Tool callTool(String name, Map<String, Object> args) { ... }
    public void close() { ... }
}
```

- JSON-RPC 2.0 over stdio 子进程
- 枚举工具、调用工具、关闭进程
- ToolCollection.fromMcp() 作为便捷门面

---

## 8. 支撑模块

### 8.1 LanguageModel — `model/LanguageModel.java`

```java
public interface LanguageModel {
    String modelId();
    ChatMessage generate(CompletionRequest request);
    default Stream<ChatMessageStreamDelta> generateStream(CompletionRequest request) { ... }
    default boolean supportsStopSequences() { return false; }
    default ChatMessage parseToolCalls(ChatMessage raw) { return raw; }
}
```

- 消除与 data model 的命名歧义
- 将复杂签名封装在 CompletionRequest record 中

### 8.2 CodeInterpreter — `executor/CodeInterpreter.java`

```java
public interface CodeInterpreter {
    void sendTools(Map<String, Tool> tools);
    void sendVariables(Map<String, Object> variables);
    ExecutionResult execute(String codeAction);
    default void cleanup() {}
    String language();
}
```

- 消除与 java.util.concurrent.Executor 的命名冲突
- 返回 ExecutionResult（含 output/logs/isFinalAnswer/error）

### 8.3 WorkingMemory — `memory/WorkingMemory.java`

```java
public interface WorkingMemory {
    void initialize(String systemPrompt);
    void add(MemoryStep step);
    List<MemoryStep> all();
    List<MemoryStep> succinct();
    List<ChatMessage> toMessages();
    List<ChatMessage> toMessages(boolean summaryMode);
    int actionStepCount();
    void truncate(int maxActionSteps);
    void reset();
    void replay(AgentLogger logger, boolean detailed);
}
```

### 8.4 Metrics — `metrics/Metrics.java`

```java
public interface Metrics {
    AgentLogger logger();
    void recordStep(ActionStep step);
    default void recordPlanning(PlanningStep step) {}
    default void recordFinalAnswer(FinalAnswerStep step) {}
    TokenUsage totalTokenUsage();
    int stepCount();
    void reset();
}
```

### 8.5 Hooks — `hooks/` 包

```java
public interface Hook {
    String name();
    default void preStep(HookContext ctx) {}
    default void postStep(HookContext ctx) {}
    default void preTool(HookContext ctx) {}
    default void postTool(HookContext ctx) {}
    default void onError(HookContext ctx) {}
    default void onFinalAnswer(HookContext ctx) {}
}

public record HookContext(
    String agentName, int stepNumber, MemoryStep currentStep,
    Tool tool, Map<String, Object> toolArgs, Object toolResult, AgentError error
) {}
```

---

## 9. 数据契约 — `spec/` 包

| Record | 字段 | 说明 |
|--------|------|------|
| `InvocationContext` | task, steps, systemPrompt, activeReminders, todoList, tokenUsage, stepNumber, state | Claude Code "Messages=State" |
| `AgentResult` | output, isFinalAnswer, isInterrupted, isMaxStepsReached, steps, state | Agent 执行结果 |
| `CompletionRequest` | messages, tools, stopSequences, responseFormat, extraParameters | 模型调用入参 |
| `ExecutionResult` | output, logs, isFinalAnswer, error | 代码执行产出 |
| `StreamEvent`(sealed) | Delta, ToolInvocation, ToolResult, ActionOutput | 流式事件 |
| `ParameterSpec` | type, description, nullable, required | 参数规格 |
| `OutputSpec` | type, description, nullable | 输出规格 |

---

## 10. 迁移路径

### Phase A（完成）: 独立模块接口
- spec/、model/、executor/、metrics/、memory/、prompt/、hooks/、tool/
- 这些模块独立，可与现有 agents/ 代码共存

### Phase B（本次）: Agent 层次重构
1. 重写 agent/Agent → 2 方法
2. 创建 AbstractAgent → 持有 model/tools/name/description
3. 创建 AbstractReActAgent → Template Method ReAct 循环（搬迁 MultiStepAgent 逻辑）
4. 创建 agent/CodeAgent → extends AbstractReActAgent（搬迁 CodeAgent 逻辑）
5. 创建 agent/ToolCallingAgent → extends AbstractReActAgent（搬迁 ToolCallingAgent 逻辑）
6. 旧 agents/MultiStepAgent、api/ 标记 @Deprecated
7. ManagedAgentTool 适配新 agent/Agent 接口

### Phase C（后续）: Skill + 上下文压缩
1. 创建 skill/ 模块
2. 实现 CompactionStrategy 4 层
3. 创建 CodeExecutionTool（CodeInterpreter 包装为 Tool）

---

## 11. 命名规范

| 概念 | Java 命名 | 类型 |
|------|-----------|------|
| Agent | `Agent` | interface |
| Agent 基类 | `AbstractAgent` | abstract class |
| ReAct 循环 | `AbstractReActAgent` | abstract class |
| 代码代理 | `CodeAgent` | class |
| 工具代理 | `ToolCallingAgent` | class |
| 规划策略 | `PlanningStrategy` | @FunctionalInterface |
| 语言模型 | `LanguageModel` | interface |
| 代码解释器 | `CodeInterpreter` | interface |
| 工作记忆 | `WorkingMemory` | interface |
| 运行指标 | `Metrics` | interface |
| 提示模板 | `PromptTemplate` | interface |
| 动态提醒 | `SystemReminder` | record |
| 生命周期钩子 | `Hook` | interface |
| 调用上下文 | `InvocationContext` | record |
| 执行结果 | `AgentResult` | record |
| 工具权限 | `ToolPermission` | enum |
| 工具集合 | `ToolCollection` | class |
| MCP 客户端 | `McpClient` | class |
