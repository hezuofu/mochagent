# MochaAgents 核心接口 OOP 架构设计

> 版本: 1.0  |  状态: 已完成实施

---

## 1. 架构总览

```
                       agent/Agent<I,O>  (泛型接口，5 方法 + 6 default 组合)
                            △
                            │ implements
                      agent/impl/BaseAgent<I,O>  (抽象类，Template Method)
                            △
                            │ extends
               agent/impl/CompositeAgent<I,O>  (组合多个 Agent)
                            △
                            │ extends
                     agent/impl/CapableAgent<I,O>  (能力完备型 Agent)
                            △
                            │ extends
                    agent/impl/MultiStepAgent  (ReAct 循环基类)
                       △              △
                       │              │
             agent/impl/CodeAgent   agent/impl/ToolCallingAgent

    横向组合（HAS-A）:
    MultiStepAgent extends CapableAgent
      ├── LLM                 (llm/)
      ├── AgentMemory         (memory/)
      ├── ToolRegistry        (tool/)
      ├── PromptTemplate      (prompt/)
      ├── ReflectionEngine    (agent/loop/reflection/)
      └── List<Tool>          (tool/)

    CapableAgent 额外持有的能力组件:
      ├── Perceptor           (perception/)
      ├── Reasoner            (reasoning/)
      ├── Planner             (plan/)
      ├── SafetyManager       (safety/)
      ├── Evaluator           (evaluation/)
      ├── MemoryManager       (memory/)
      └── Learner             (learn/)

    数据契约:
    agent/AgentContext, agent/AgentMetadata, agent/AgentEvent, agent/AgentListener
    agent/loop/StepResult, agent/loop/LoopState, agent/loop/TerminationCondition
    agent/loop/step/MemoryStep (sealed) → SystemPromptStep, TaskStep, PlanningStep, ActionStep, FinalAnswerStep
    llm/LLMRequest, llm/LLMResponse, llm/StreamingResponse

    独立模块:
    prompt/PromptTemplate    ({key} 插值渲染器)
    tool/Tool, ToolInput, ToolRegistry
    llm/LLM (接口) → provider/ (8 个实现)
    memory/AgentMemory, MemoryManager, MemoryStore, MemoryEntry
```

设计原则：
- **接口隔离**：Agent 只定义执行和元数据，具体组件通过组合注入
- **Template Method**：BaseAgent.execute() 封装生命周期，子类覆写 doExecute()
- **组合优先**：CapableAgent 通过 Builder 注入 9 种能力组件
- **函数式友好**：Agent 接口提供 6 种 default 组合方法，TerminationCondition 用 @FunctionalInterface

---

## 2. Agent 模块

### 2.1 Agent 接口 — `agent/Agent.java`

```java
public interface Agent<I, O> {
    O execute(I input);
    CompletableFuture<O> executeAsync(I input);
    AgentMetadata metadata();
    void addListener(AgentListener<I, O> listener);
    void removeListener(AgentListener<I, O> listener);

    // 函数式组合 (default 方法)
    <T> Agent<T, O> before(Function<T, I> mapper);
    <T> Agent<I, T> after(Function<O, T> mapper);
    <T> Agent<I, T> andThen(Agent<O, T> next);
    Agent<I, O> when(Predicate<I> condition, Agent<I, O> alternative);
    Agent<I, O> withRetry(int maxAttempts);
    Agent<I, O> withTimeout(long timeoutMillis);
}
```

- 泛型 `I`, `O` — 输入输出类型灵活
- 核心 2 个执行方法（sync/async） + metadata + listener 管理 + 6 种函数式组合方法
- `before/after/andThen` 提供类型映射与链式组合
- `when/withRetry/withTimeout` 提供条件分支、重试与超时控制
- 组合方法返回匿名实现类，listener 通常为 no-op

### 2.2 AgentState — `agent/AgentState.java`

```java
public enum AgentState {
    IDLE, INITIALIZING, RUNNING, WAITING,
    PAUSED, COMPLETED, FAILED, CANCELLED, DESTROYED
}
```

### 2.3 AgentContext — `agent/AgentContext.java`

```java
public final class AgentContext {  // Builder 模式
    String sessionId, userId, userMessage, conversationHistory;
    Map<String, Object> metadata;
    Instant timestamp;
}
```

- 封装单次调用的会话、用户、消息上下文
- 不可变对象（final class + private constructor）

### 2.4 AgentMetadata — `agent/AgentMetadata.java`

```java
public final class AgentMetadata {  // Builder 模式
    String name, version, description;
    Set<String> capabilities;
    String modelInfo;

    AgentMetadata and(AgentMetadata other);  // 合并元数据
}
```

### 2.5 AgentEvent — `agent/AgentEvent.java`

```java
public final class AgentEvent<T> {
    String source();
    T data();
    long timestamp();
}
```

- 泛型事件载体

### 2.6 AgentListener — `agent/AgentListener.java`

```java
public interface AgentListener<I, O> {
    default void onStart(AgentEvent<I> event) {}
    default void onComplete(AgentEvent<O> event) {}
    default void onError(AgentEvent<Throwable> event) {}
    default void onProgress(double progress) {}
}
```

- 全部 default 方法，实现类按需覆写

### 2.7 BaseAgent — `agent/impl/BaseAgent.java`

```java
public abstract class BaseAgent<I, O> implements Agent<I, O> {
    protected final String name, description;
    protected final List<AgentListener<I, O>> listeners = new CopyOnWriteArrayList<>();
    protected volatile AgentState state = AgentState.IDLE;

    // Template Method
    protected abstract O doExecute(I input);

    @Override
    public O execute(I input) {
        state = RUNNING; fireStart(input);
        try { O r = doExecute(input); state = COMPLETED; fireComplete(r); return r; }
        catch (Exception e) { state = FAILED; fireError(e); throw e; }
    }

    @Override
    public CompletableFuture<O> executeAsync(I input) {
        return CompletableFuture.supplyAsync(() -> execute(input));
    }

    // self-referential generic Builder
    public abstract static class Builder<I, O, T extends Builder<I, O, T>> { ... }
}
```

- Template Method：`execute()` 管理状态机 + 监听器通知，子类覆写 `doExecute()`
- Builder 泛型自引用，支持子类链式扩展

### 2.8 CompositeAgent — `agent/impl/CompositeAgent.java`

```java
public class CompositeAgent<I, O> implements Agent<I, List<O>> {
    CompositeAgent(List<Agent<I, O>> agents);
    CompositeAgent(List<Agent<I, O>> agents, String name);
    static <I, O> CompositeAgent<I, O> of(Agent<I, O>... agents);
}
```

- 直接实现 Agent 接口（不继承 BaseAgent），将多个子 Agent 顺序执行
- `executeAsync` 使用 `CompletableFuture.allOf` 并行执行
- listener 转发给所有子 Agent

### 2.9 CapableAgent — `agent/impl/CapableAgent.java`

```java
public abstract class CapableAgent<I, O> extends BaseAgent<I, O> {
    protected final Perceptor<I, O> perceptor;
    protected final Reasoner reasoner;
    protected final Planner<O> planner;
    protected final ToolRegistry toolRegistry;
    protected final SafetyManager safetyManager;
    protected final Evaluator evaluator;
    protected final MemoryManager memoryManager;
    protected final Learner<I, O> learner;
    protected final ReflectionEngine reflectionEngine;

    // 带 Context 的完整执行
    public O execute(I input, ContextManager ctx) { ... }
    // 向后兼容
    @Override public O execute(I input) { return execute(input, defaultCtx); }

    // 子类钩子
    protected abstract O doExecute(I input, ContextManager ctx);
}
```

- 通过 Builder 注入 9 种能力组件
- `execute(I, ContextManager)` 编排 8 步流水线：注入记忆 → 感知 → 推理 → 规划 → 安全校验 → 工具调用 → Agent 逻辑 → 评估 → 反思 → 学习 → 压缩
- 每一步都是 `protected` 方法，子类可选择性覆写
- Builder 继承链：`BaseAgent.Builder<I,O,T> → CapableAgent.Builder<I,O,T>`

### 2.10 MultiStepAgent — `agent/impl/MultiStepAgent.java`

```java
public abstract class MultiStepAgent extends CapableAgent<String, String> {
    protected final LLM llm;
    protected final AgentMemory memory = new AgentMemory();
    protected final int maxSteps, planningInterval;
    protected final boolean addBaseTools;
    protected PromptTemplate systemPromptTemplate, planningPromptTemplate,
                           finalAnswerPreTemplate, finalAnswerPostTemplate;

    public String run(String task);
    public String run(String task, int maxSteps);
    public String buildSystemPrompt();
    public AgentMemory memory();

    // 子类核心钩子
    protected abstract StepResult executeReActStep(int stepNumber, String input, AgentMemory memory);
}
```

- 固定泛型为 `<String, String>` — 文本输入输出
- `run()` 内部实例化 `ReActLoop`，注入 `planStep` 和 `executeReActStep`
- 超出最大步数时通过 `provideFinalAnswer()` 生成兜底答案
- 内部注册 `FinalAnswerTool` 到 toolRegistry

### 2.11 CodeAgent — `agent/impl/CodeAgent.java`

```java
public final class CodeAgent extends MultiStepAgent {
    private final Set<String> authorizedImports;
    private final String codeLanguage;
    private final int maxPrintOutputLength;

    @Override protected StepResult executeReActStep(int stepNumber, String input, AgentMemory memory);
}
```

- 解析 LLM 输出中的 `<code>...</code>` 或 ` ```python ``` ` 代码块
- 执行策略：ScriptEngine（nashorn→graal.js→JavaScript）→ 模拟工具调用 → 降级返回
- 支持 `final_answer()` 检测（多种引号格式 + 无引号变量引用）
- 工具注入：将 ToolRegistry 中的工具包装为 `ToolFunction` 注入 ScriptEngine

### 2.12 ToolCallingAgent — `agent/impl/ToolCallingAgent.java`

```java
public final class ToolCallingAgent extends MultiStepAgent {
    @Override protected StepResult executeReActStep(int stepNumber, String input, AgentMemory memory);
}
```

- 解析 LLM 输出中的 `Thought: ... Action: tool_name(args)` 或 JSON tool-call 格式
- 执行解析出的工具调用，`final_answer` 作为特殊工具终止循环

### 2.13 Builder 继承链

```
BaseAgent.Builder<I,O,T>
    └── CapableAgent.Builder<I,O,T>    ← 注入 perceptor/reasoner/planner/...
            └── MultiStepAgent.Builder<T>  ← 注入 llm/tools/maxSteps
                    ├── CodeAgent.Builder
                    └── ToolCallingAgent.Builder
```

所有 Builder 使用泛型自引用 `T extends Builder<?,?,T>` 支持子类链式调用。

---

## 3. Agentic Loop 模块

### 3.1 AgenticLoop — `agent/loop/AgenticLoop.java`

```java
public interface AgenticLoop<I, O> {
    O run(Agent<I, O> agent, I input, TerminationCondition condition);
    StepResult step(Agent<I, O> agent, I input, int stepNum);
}
```

- 自主循环接口，封装循环执行生命周期

### 3.2 LoopState — `agent/loop/LoopState.java`

```java
public enum LoopState {
    INIT, OBSERVE, PLAN, ACT, REFLECT, COMPLETE, ERROR, INTERRUPTED
}
```

### 3.3 StepResult — `agent/loop/StepResult.java`

```java
public final class StepResult {  // Builder 模式
    int stepNumber, durationMs;
    LoopState state;
    String observation, action, output, error;
    boolean hasError();
}
```

### 3.4 TerminationCondition — `agent/loop/TerminationCondition.java`

```java
@FunctionalInterface
public interface TerminationCondition {
    boolean shouldTerminate(StepResult result);

    static TerminationCondition maxSteps(int maxSteps);    // 步数限制
    static TerminationCondition onError();                  // 遇错即停
    default TerminationCondition or(TerminationCondition);  // OR 组合
    default TerminationCondition and(TerminationCondition); // AND 组合
}
```

### 3.5 ReActLoop（实现类）— `agent/loop/impl/ReActLoop.java`

```java
public class ReActLoop<I, O> implements AgenticLoop<I, O> {
    @FunctionalInterface interface StepExecutor<I> {
        StepResult execute(int stepNumber, I input, AgentMemory memory);
    }
    @FunctionalInterface interface PlanningFn<I> {
        String plan(int stepNumber, I input, AgentMemory memory);
    }
}
```

- 通过反射调用 Agent 的 `memory()` 和 `buildSystemPrompt()` 方法获取记忆和系统提示
- 循环体内：可选规划 → 调用 stepExecutor → 检查终止条件 → 检查 final_answer
- `MultiStepAgent.run()` 内部实例化此类

### 3.6 循环策略 — `agent/loop/strategy/`

| 类 | 实现接口 | 说明 |
|---|---|---|
| `strategy/ReActLoop` | `AgenticLoop<I,O>` | 泛型 ReAct，每步执行 agent.execute(input) |
| `ObservePlanActReflect` | `AgenticLoop<I,O>` | OPAR 四阶段循环 |
| `ThinkActObserve` | `AgenticLoop<I,O>` | TAO 三步循环 |

注意：`MultiStepAgent` 实际使用的是 `agent/loop/impl/ReActLoop`（非策略包中的版本），后者通过 `StepExecutor` 函数式接口与 Agent 的具体单步逻辑解耦。

### 3.7 反思模块 — `agent/loop/reflection/`

```java
@FunctionalInterface
public interface ReflectionEngine {
    ImprovementPlan reflect(StepResult result, SelfCritique critique);
    static ReflectionEngine noop();
}

public final class SelfCritique {  // Builder
    String analysis; boolean needsImprovement; String suggestion; double confidence;
}

public final class ImprovementPlan {  // Builder
    String summary; List<String> changes;
    static ImprovementPlan empty();
}
```

### 3.8 MemoryStep（步类型 sealed 接口）— `agent/loop/step/`

```java
public sealed interface MemoryStep
    permits SystemPromptStep, TaskStep, PlanningStep, ActionStep, FinalAnswerStep {
    String type();
}

public record SystemPromptStep(String systemPrompt) implements MemoryStep {}
public record TaskStep(String task) implements MemoryStep {}
public record PlanningStep(String plan, String modelOutput, int inputTokens, int outputTokens) implements MemoryStep {}
public record ActionStep(int stepNumber, String modelInput, String modelOutput,
    String action, String observation, String error, int inputTokens,
    int outputTokens, boolean isFinalAnswer) implements MemoryStep {}
public record FinalAnswerStep(Object output) implements MemoryStep {}
```

- `MemoryStep` 是 sealed 接口，5 种 record 实现，对应 smolagents 的步记录体系
- `ActionStep` 是最核心的记录，包含思考-行动-观察的完整信息

---

## 4. LLM 模块

### 4.1 LLM 接口 — `llm/LLM.java`

```java
public interface LLM {
    LLMResponse complete(LLMRequest request);
    CompletableFuture<LLMResponse> completeAsync(LLMRequest request);
    StreamingResponse stream(LLMRequest request);
    String modelName();
    int maxContextTokens();
}
```

### 4.2 LLMRequest — `llm/LLMRequest.java`

```java
public class LLMRequest {  // Builder
    String prompt();                    // 单 prompt 模式
    List<Map<String, String>> messages(); // messages 模式
    double temperature();
    int maxTokens();
    double topP();
    List<String> stopSequences();
    Map<String, Object> extraParams();
}
```

### 4.3 LLMResponse — `llm/LLMResponse.java`

```java
public class LLMResponse {
    String content(), model();
    int promptTokens(), completionTokens(), totalTokens();
    long latencyMs();
    Map<String, Object> metadata();
    static LLMResponse of(String content);
}
```

### 4.4 StreamingResponse — `llm/StreamingResponse.java`

```java
public class StreamingResponse implements Iterable<String> {
    void push(String token);
    void complete();
    void error(Throwable t);
    void subscribe(Consumer<String> onToken, Consumer<Throwable> onError, Runnable onComplete);
}
```

- 基于 `BlockingQueue` 的 token 流

### 4.5 提供商 — `llm/provider/`

| 类 | 说明 |
|---|---|
| `BaseApiLLM` | OkHttp + Jackson 共享传输层基类 |
| `OpenAILLM` | OpenAI 兼容 API (gpt-4o, gpt-4, gpt-3.5) |
| `AnthropicLLM` | Anthropic API (Claude 3) |
| `DeepSeekLLM` | DeepSeek API (V2/V3/R1) |
| `QwenLLM` | 通义千问 API |
| `LocalLLM` | Ollama local models via `/v1` |
| `OpenAICompatibleLLM` | 任意 OpenAI 兼容端点 |
| `MockLLM` | 确定性响应（测试用） |

### 4.6 路由 — `llm/router/`

```java
public class LLMRouter {
    void register(String name, LLM llm);
    LLM route(LLMRequest request);
    LLM routeWithFallback(LLMRequest request);
    Collection<LLM> getProviders();
}

public class CostOptimizer { ... }     // 简单成本选择
public class FallbackStrategy { ... }  // 降级策略
```

---

## 5. Tool 模块

### 5.1 Tool — `tool/Tool.java`

```java
public interface Tool {
    String getName();
    String getDescription();
    Map<String, ToolInput> getInputs();
    String getOutputType();
    Object call(Map<String, Object> arguments);
    SecurityLevel getSecurityLevel();

    enum SecurityLevel { LOW, MEDIUM, HIGH, CRITICAL }
}
```

### 5.2 ToolInput — `tool/ToolInput.java`

```java
public final class ToolInput {
    String type(), description(); boolean nullable();
    static ToolInput string(String desc);
    static ToolInput integer(String desc);
    static ToolInput booleanInput(String desc);
    static ToolInput any(String desc);
}
```

### 5.3 ToolRegistry — `tool/ToolRegistry.java`

```java
public class ToolRegistry {
    void register(Tool tool);
    Tool get(String name);
    boolean has(String name);
    Collection<Tool> all();
}
```

- 基于 `ConcurrentHashMap`，线程安全

---

## 6. 记忆模块

### 6.1 AgentMemory — `memory/AgentMemory.java`

```java
public class AgentMemory {
    void setSystemPrompt(String prompt);
    String systemPrompt();
    void append(MemoryStep step);
    List<MemoryStep> steps();
    void reset(); void reset(String newSystemPrompt);
    int size();
    MemoryStep lastStep();
    boolean hasFinalAnswer();

    // 便捷追加
    void appendSystemPrompt(String), appendTask(String),
         appendPlanning(String, String, int, int), appendAction(ActionStep),
         appendFinalAnswer(Object);

    // 持久化桥接
    List<Memory> snapshot();
    void restore(List<Memory> entries);
}
```

- 所有 Agent 的通用执行轨迹记录器，记录完整思考-行动-观察历史
- `snapshot()` 将 ActionStep 提取为情景记忆、FinalAnswerStep 提取为语义记忆

### 6.2 Memory 接口 — `memory/Memory.java`

```java
public interface Memory {
    String id(), content(), TYPE_WORKING/TYPE_EPISODIC/TYPE_SEMANTIC;
    String type();
    Instant createdAt(), lastAccessedAt();
    int accessCount();
    double importance();
    Set<String> tags();
    Map<String, Object> metadata();
    void touch();
}
```

### 6.3 MemoryEntry — `memory/MemoryEntry.java`

```java
public class MemoryEntry implements Memory {  // Builder
    static MemoryEntry working(String content);
    static MemoryEntry episodic(String content, Map<String, Object> metadata);
    static MemoryEntry semantic(String content, Set<String> tags);
    void setImportance(double);
}
```

### 6.4 MemoryStore — `memory/MemoryStore.java`

```java
public interface MemoryStore {
    void store(Memory m); Optional<Memory> get(String id); void forget(String id);
    void clear(String type); int size();
    List<Memory> search(String query); List<Memory> getByType(String type);
    List<Memory> searchByTag(String tag);
}
```

实现: `InMemoryMemoryStore` (ConcurrentHashMap), `MarkdownMemoryStore` (Markdown 文件)

### 6.5 MemoryManager — `memory/MemoryManager.java`

```java
public class MemoryManager {
    MemoryManager(); MemoryManager(MemoryStore, int maxWorking, int maxEpisodic);
    void store(Memory); Optional<Memory> get(String); void forget(String);
    void clear(String); int size();
    List<Memory> search(String), getByType(String), searchByTag(String);
    List<Memory> retrieve(String context, List<String> tags, int maxResults);
    List<Memory> hybridRetrieve(String query, int maxResults);
    List<Memory> recentEpisodes(int n);
    double score(Memory);            // 综合重要性评分
    String compressContent(String);  // 超长内容截断
}
```

- 协调层，委托 CRUD 给 MemoryStore，提供高级检索和重要性评分
- 自动容量控制：溢出时按重要性淘汰

---

## 7. 上下文字段模块

### 7.1 ContextChunk — `context/ContextChunk.java`

```java
public record ContextChunk(String id, String role, String content, int tokenCount) {}
```

### 7.2 ContextWindow — `context/ContextWindow.java`

```java
public class ContextWindow {
    ContextWindow(int maxTokens);
    void add(ContextChunk chunk);
    List<ContextChunk> all();
    void clear();
    int tokenCount();
    int maxTokens();
}
```

### 7.3 ContextStrategy — `context/ContextStrategy.java`

```java
@FunctionalInterface
public interface ContextStrategy {
    List<ContextChunk> apply(List<ContextChunk> chunks, int maxTokens);
}
```

实现: `SlidingWindowStrategy`, `SummarizationStrategy`, `HybridContextStrategy`

### 7.4 ContextCompressor — `context/ContextCompressor.java`

```java
@FunctionalInterface
public interface ContextCompressor {
    List<ContextChunk> compress(List<ContextChunk> chunks, int maxTokens);
}
```

### 7.5 ContextManager — `context/ContextManager.java`

```java
public class ContextManager {
    ContextManager(int maxTokens, ContextStrategy strategy, ContextCompressor compressor);
    void addChunk(ContextChunk);
    List<ContextChunk> getContext();
    void compress();
    void clear();
    int tokenCount();
    int maxTokens();
}
```

---

## 8. 推理与规划模块

### 8.1 Reasoner — `reasoning/Reasoner.java`

```java
public interface Reasoner {
    ReasoningChain reason(String question);
    void setStrategy(ReasoningStrategy strategy);
    ReasoningStrategy getStrategy();
}
```

策略实现: `ChainOfThought`, `TreeOfThought`, `ProgramOfThought`, `GraphOfThought`

### 8.2 Planner — `plan/Planner.java`

```java
public interface Planner<T> {
    Plan<T> generatePlan(PlanningRequest<T> request);
    CompletableFuture<Plan<T>> generatePlanAsync(PlanningRequest<T> request);
    Plan<T> replan(Plan<T> currentPlan, ExecutionFeedback feedback);
    PlanningStrategy getStrategy();
    void setStrategy(PlanningStrategy strategy);
}
```

### 8.3 Plan — `plan/Plan.java`

```java
public interface Plan<T> {
    String getPlanId(); T getGoal();
    List<PlanStep> getSteps(); PlanStep getCurrentStep();
    void advance();
    PlanStep.PlanStatus getStatus(); void setStatus(PlanStep.PlanStatus);
    DependencyGraph getDependencyGraph();
    long estimateExecutionTime();
    ValidationResult validate();
    String serialize();
}
```

策略实现: `HierarchicalPlanner`, `AdaptivePlanner`, `ReplanningStrategy`

---

## 9. 感知模块

### 9.1 Perceptor — `perception/Perceptor.java`

```java
public interface Perceptor<I, O> {
    PerceptionResult<O> perceive(I input);
    CompletableFuture<PerceptionResult<O>> perceiveAsync(I input);
    default Perceptor<I, O> filter(Predicate<O> filter);
}
```

处理器: `BrowserPerceptor`, `CodebasePerceptor`, `FileSystemPerceptor`, `TerminalPerceptor`

---

## 10. 安全模块

### 10.1 SafetyManager — `safety/SafetyManager.java`

```java
public class SafetyManager {
    SafetyManager(); SafetyManager(ContentFilter, CodeValidator, Sandbox);
    boolean checkContent(String content);
    boolean validateCode(String code);
    String safeExecute(String code, String language);
    void addBlockedPattern(String pattern);
}
```

### 10.2 子组件

```java
public class ContentFilter { boolean isSafe(String content); }
public class CodeValidator { boolean validate(String code); }
public class Sandbox { String execute(String code, String language); }
```

---

## 11. 评估模块

### 11.1 Evaluator — `evaluation/Evaluator.java`

```java
public interface Evaluator {
    EvaluationResult evaluate(String input, String output, String expected);
    EvaluationCriteria getCriteria();
}
```

评判器: `LLMJudge`, `HumanJudge`, `AutomatedJudge`

指标: `QualityMetrics`, `SafetyMetrics`, `PerformanceMetrics`, `HallucinationDetector`

---

## 12. 学习模块

### 12.1 Learner — `learn/Learner.java`

```java
public interface Learner<I, O> {
    void learn(Experience<I, O> experience);
    void learnBatch(List<Experience<I, O>> experiences);
    O infer(I input);
    LearningStrategy getStrategy();
    void setStrategy(LearningStrategy strategy);
    int experienceCount();
}
```

策略: `FewShotLearner`, `FeedbackLearner`, `CurriculumLearner`

经验管理: `ExperienceBuffer`, `ExperienceReplay`

---

## 13. 交互模块

### 13.1 Interactor — `interaction/Interactor.java`

```java
public interface Interactor {
    void send(String message); String receive(); String ask(String prompt);
    boolean confirm(String prompt);
    InteractionMode getMode(); void setMode(InteractionMode mode);
    void showProgress(String message, double progress); void showError(String error);
}
```

模式: `AutonomousMode`, `CollaborativeMode`, `SupervisedMode`

---

## 14. 编排模块

### 14.1 Orchestrator — `orchestration/Orchestrator.java`

```java
public interface Orchestrator {
    void register(Agent<?, ?> agent, Role role);
    void unregister(String agentId);
    <I, O> O orchestrate(I input, OrchestrationStrategy strategy);
    <I, O> CompletableFuture<O> orchestrateAsync(I input, OrchestrationStrategy strategy);
    AgentTeam getTeam();
    OrchestrationStrategy getStrategy();
    void shutdown();
}
```

### 14.2 AgentTeam — `orchestration/AgentTeam.java`

```java
public class AgentTeam {
    AgentTeam addMember(Agent<?, ?> agent, Role role);
    void removeMember(String agentId);
    Collection<Agent<?, ?>> getAgents();
    <T extends Agent<?, ?>> List<T> getByRole(RoleType roleType);
    <T extends Agent<?, ?>> List<T> getLeaders();
}
```

### 14.3 Role — `orchestration/Role.java`

```java
public class Role {
    Role(String name, RoleType type, String description, Set<String> capabilities);
    static Role leader(String name);    // LEADER
    static Role worker(String name, String... capabilities); // WORKER
    static Role reviewer(String name);  // REVIEWER
    static Role observer(String name);  // OBSERVER
}

public enum RoleType { LEADER, WORKER, REVIEWER, EXECUTOR, OBSERVER }
```

策略: `SupervisorAgent`, `SwarmStrategy`, `DebateStrategy`
通信: `AgentMessage`, `MessageBus`, `NegotiationProtocol`

---

## 15. 监控模块

### 15.1 Telemetry — `monitor/Telemetry.java`

```java
public interface Telemetry {
    void trackEvent(String name, Map<String, Object> properties);
    void trackMetric(String name, double value);
    void trackException(Throwable throwable);
    void startOperation(String name); void endOperation(String name);
}
```

### 15.2 MetricsCollector — `monitor/MetricsCollector.java`

```java
public class MetricsCollector {
    void incrementCounter(String name);
    void recordValue(String name, double value);
    long getCounter(String name);
    MetricSummary getSummary(String name);
    void reset();
    record MetricSummary(String name, int count, double sum, double avg, double min, double max) {}
}
```

---

## 16. Prompt 模块

### 16.1 PromptTemplate — `prompt/PromptTemplate.java`

```java
public final class PromptTemplate {
    PromptTemplate(String template);
    String render(Map<String, Object> variables);
    String render(String k1, Object v1, String k2, Object v2);
    String render(String key, Object value);
    String template();
    static PromptTemplate of(String template);
}
```

- 基于 `{key}` 占位符的简单字符串替换，不引入外部模板引擎
- 支持 Map、双变量、单变量三种渲染模式

---

## 17. 完整文件清单

### agent/ (8 个文件)

| 文件 | 类型 | 说明 |
|------|------|------|
| `Agent.java` | interface (泛型) | 核心 Agent 接口 |
| `AgentContext.java` | final class (Builder) | 执行上下文 |
| `AgentEvent.java` | final class | 事件载体 |
| `AgentListener.java` | interface (泛型) | 事件监听器 |
| `AgentMetadata.java` | final class (Builder) | 元数据 |
| `AgentState.java` | enum | 生命周期状态 |
| `impl/BaseAgent.java` | abstract class | Template Method 基类 |
| `impl/CapableAgent.java` | abstract class | 能力完备型基类 |
| `impl/CompositeAgent.java` | class | 组合 Agent |
| `impl/MultiStepAgent.java` | abstract class | ReAct 循环基类 |
| `impl/CodeAgent.java` | final class | 代码执行 Agent |
| `impl/ToolCallingAgent.java` | final class | 工具调用 Agent |
| `loop/AgenticLoop.java` | interface | 循环接口 |
| `loop/LoopState.java` | enum | 循环状态 |
| `loop/StepResult.java` | final class (Builder) | 步骤结果 |
| `loop/TerminationCondition.java` | @FunctionalInterface | 终止条件 |
| `loop/impl/ReActLoop.java` | class | ReAct 实现 |
| `loop/strategy/ReActLoop.java` | class | 泛型 ReAct 策略 |
| `loop/strategy/ObservePlanActReflect.java` | class | OPAR 策略 |
| `loop/strategy/ThinkActObserve.java` | class | TAO 策略 |
| `loop/reflection/ReflectionEngine.java` | @FunctionalInterface | 反思引擎 |
| `loop/reflection/SelfCritique.java` | final class (Builder) | 自我批评 |
| `loop/reflection/ImprovementPlan.java` | final class (Builder) | 改进计划 |
| `loop/step/MemoryStep.java` | sealed interface | 步类型 |
| `loop/step/SystemPromptStep.java` | record | 系统提示 |
| `loop/step/TaskStep.java` | record | 任务输入 |
| `loop/step/PlanningStep.java` | record | 规划步骤 |
| `loop/step/ActionStep.java` | record | 行动步骤 |
| `loop/step/FinalAnswerStep.java` | record | 最终答案 |

### 其余模块核心文件

| 模块 | 核心文件数 | 关键类 |
|------|-----------|--------|
| `llm/` | 6 | LLM, LLMRequest, LLMResponse, StreamingResponse, router/ |
| `llm/provider/` | 8 | BaseApiLLM, OpenAILLM, AnthropicLLM, DeepSeekLLM, QwenLLM, LocalLLM, OpenAICompatibleLLM, MockLLM |
| `tool/` | 8 | Tool, ToolInput, ToolRegistry + category/ + workbench/ + mcp/ |
| `memory/` | 8 | AgentMemory, Memory, MemoryEntry, MemoryStore, MemoryManager, InMemoryMemoryStore, MarkdownMemoryStore |
| `context/` | 6 | ContextChunk, ContextWindow, ContextStrategy, ContextCompressor, ContextManager + strategy/ |
| `reasoning/` | 6 | Reasoner, ReasoningChain, ReasoningStep, ReasoningStrategy + strategy/ + verifier/ |
| `plan/` | 12 | Planner, Plan, PlanStep, DynamicPlanner, DependencyGraph + strategy/ + decomposer/ |
| `perception/` | 6 | Perceptor, PerceptionResult + processor/ + fusion/ |
| `safety/` | 6 | SafetyManager, ContentFilter, CodeValidator, Sandbox + audit/ + policy/ |
| `evaluation/` | 5 | Evaluator, EvaluationCriteria, EvaluationResult + judge/ + metrics/ |
| `learn/` | 5 | Learner, Experience, LearningStrategy + strategy/ + experience/ |
| `interaction/` | 5 | Interactor, InteractionMode + mode/ + permission/ |
| `orchestration/` | 7 | Orchestrator, AgentTeam, Role, OrchestrationStrategy + strategy/ + communication/ |
| `monitor/` | 5 | Telemetry, MetricsCollector, Tracer + dashboard/ |
| `prompt/` | 1 | PromptTemplate |
| `examples/` | 23 | 4 个示例 Agent + 16 个 Example + LLMFactory + GroqTest + tools/ |

**总计: 194 个 Java 源文件**

---

## 18. 命名规范

| 概念 | Java 命名 | 类型 |
|------|-----------|------|
| Agent | `Agent<I,O>` | interface (泛型) |
| Agent 基类 | `BaseAgent<I,O>` | abstract class |
| 能力完备 Agent | `CapableAgent<I,O>` | abstract class |
| ReAct Agent 基类 | `MultiStepAgent` | abstract class (固定 String,String) |
| 代码 Agent | `CodeAgent` | final class |
| 工具调用 Agent | `ToolCallingAgent` | final class |
| 组合 Agent | `CompositeAgent<I,O>` | class |
| 自主循环 | `AgenticLoop<I,O>` | interface |
| 循环实现 | `ReActLoop<I,O>` | class |
| 终止条件 | `TerminationCondition` | @FunctionalInterface |
| 反思引擎 | `ReflectionEngine` | @FunctionalInterface |
| 步骤记录 | `MemoryStep` | sealed interface |
| 语言模型 | `LLM` | interface |
| 工具 | `Tool` | interface |
| Agent 记忆 | `AgentMemory` | class |
| 记忆管理 | `MemoryManager` | class |
| 上下文管理 | `ContextManager` | class |
| 感知器 | `Perceptor<I,O>` | interface |
| 推理器 | `Reasoner` | interface |
| 规划器 | `Planner<T>` | interface |
| 编排器 | `Orchestrator` | interface |
| 安全管理 | `SafetyManager` | class |
| 评估器 | `Evaluator` | interface |
| 学习器 | `Learner<I,O>` | interface |
| 交互器 | `Interactor` | interface |
| 提示模板 | `PromptTemplate` | final class |
| Agent 团队 | `AgentTeam` | class |
| 角色 | `Role` | class |
