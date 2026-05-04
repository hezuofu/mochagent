# MochaAgent

Java AI agent framework — claude-code patterns + smolagents architecture, production-grade.

## 5 秒开始

```java
// 自动检测 LLM（OPENAI_API_KEY → ANTHROPIC_API_KEY → DeepSeek → Groq → Ollama）
var llm = LLMFactory.create();

// 一行构建+运行
String answer = MochaAgent.builder()
    .name("assistant").llm(llm)
    .addTool(new WebSearchTool())
    .build()
    .run("What is the weather in Tokyo?");
```

## 使用指南

### 统一入口 — MochaAgent

所有 Agent 类型通过一个 Builder 创建，实现 `Agent<String,String>` 接口，可参与链式组合。

```java
// ToolCalling Agent — LLM 生成工具调用
MochaAgent agent = MochaAgent.builder()
    .name("my-agent").llm(llm).toolCalling()
    .addTool(new WebSearchTool())
    .maxSteps(10).build();

// Code Agent — LLM 写代码执行
MochaAgent coder = MochaAgent.builder()
    .name("coder").llm(llm).code()
    .maxSteps(15).build();

// 链式组合
Agent<String, String> pipeline = agent.andThen(coder);
Agent<String, String> robust = agent.withRetry(3).withTimeout(60_000);
```

### 执行范式

一行切换到任意范式：

```java
// Reflexion — 每步自我批评+改进
MochaAgent reflective = MochaAgent.builder().name("r").llm(llm)
    .reflexionLoop().build();

// ReWOO — 一次性推理+批量执行（仅2次LLM调用）
MochaAgent rewoo = MochaAgent.builder().name("w").llm(llm)
    .rewooLoop(reasoner, toolExecutor, synthesizer).build();

// 自定义范式
MochaAgent custom = MochaAgent.builder().name("c").llm(llm)
    .loop(new ObservePlanActReflect<>(observer, planner, executor, engine, 3))
    .build();
```

| 范式 | 类 | 特点 |
|------|-----|------|
| ReAct | `ReActLoop` (默认) | Think → Act → Observe 迭代 |
| Reflexion | `ReflexionLoop` | Act → Self-Critique → Improve |
| ReWOO | `ReWOOLoop` | Reason全量 → Batch执行 → Synthesize |
| TAO | `ThinkActObserve` | 三步简化ReAct |
| OPAR | `ObservePlanActReflect` | 四步+周期性反思 |

### 注入能力

```java
MochaAgent agent = MochaAgent.builder().name("engineer").code().llm(llm)
    .reasoner(new DefaultReasoner(llm))           // CoT→TreeOfThought回退链
    .planner(new DynamicPlanner<>(                 // 计划跟踪+偏差重规划
        new AdaptivePlanner(llm)))
    .perceptor(new CodebasePerceptor())            // 每步环境感知
    .thinkingConfig(ThinkingConfig.adaptive())     // 自适应思考
    .effortLevel(EffortLevel.HIGH)                 // 推理深度
    .permissionRules(new PermissionRules()         // 权限规则
        .add("rm*", Behavior.DENY, Source.POLICY))
    .memoryManager(new MemoryManager(              // 持久化记忆
        new InMemoryMemoryStore()))
    .build();
```

### 多 Agent 委托

```java
MochaAgent engineer = MochaAgent.builder().name("engineer").code().llm(llm).build();
MochaAgent reviewer = MochaAgent.builder().name("reviewer").toolCalling().llm(llm).build();

// 主Agent自动发现子Agent工具
ToolCallingAgent lead = ToolCallingAgent.builder().name("lead").llm(llm)
    .managedAgents(List.of((ReActAgent) engineer.inner(),
                           (ReActAgent) reviewer.inner()))
    .build();
// LLM 可直接调用: delegate_engineer(task="...")
```

### AgentContext — 会话语境

```java
AgentContext ctx = AgentContext.builder()
    .sessionId("sess-001").userId("user-42")
    .userMessage("Fix the auth bug")
    .conversationHistory("User: where is the login code?\nAssistant: in AuthModule.java")
    .metadata("instructions", "Use TDD approach")
    .build();

agent.run(ctx);
```

### 工具注册

```java
// 引导时自动注册14个内置工具
var bootstrap = AgentBootstrap.init(llm);
// bash, read, write, edit, glob, grep, web_fetch, web_search,
// calculator, bug_check, todo_write, agent, skill, final_answer

// 自定义工具
bootstrap.toolRegistry().register(new Tool() {
    public String getName() { return "my_tool"; }
    public String getDescription() { return "Does something useful"; }
    public Object call(Map<String, Object> args) { return "result"; }
    // ...
});

// 使用时注入
var agent = MochaAgent.builder().name("a").llm(llm)
    .toolRegistry(bootstrap.toolRegistry()).build();
```

### 流式执行

```java
agent.runStreaming(ctx, token -> {
    System.out.print(token);  // 实时输出每个LLM token
});
```

### 事件订阅

```java
agent.inner().onEvent(e -> {
    switch (e.type()) {
        case AgentEvents.TOOL_CALL -> {
            var data = (Map<String, Object>) e.data();
            System.out.println("Tool: " + data.get("toolName"));  // 含diff
        }
        case AgentEvents.COST -> {
            double[] c = (double[]) e.data();
            System.out.printf("$%.4f | %dtk%n", c[0], (long) c[1]);
        }
    }
});
```

### 权限 + 钩子

```java
var agent = ToolCallingAgent.builder().name("a").llm(llm)
    .permissionRules(new PermissionRules()
        .add("rm*", Behavior.DENY, Source.POLICY))
    .build();

agent.hooks().onPreTool((tool, args) -> {
    if ("bash".equals(tool.getName()) && args.toString().contains("sudo"))
        return HookDecision.deny("sudo blocked");
    return HookDecision.allow(args);
});
```

### 计划模式

```java
// CLI: /plan → 进入只读探索 → /exitplan → 批准写入
// 编程:
PlanMode planMode = new PlanMode();
planMode.enterPlan();                         // Phase 1: UNDERSTAND
// ... agent explores codebase ...
planMode.advancePhase();                      // Phase 2 → DESIGN
planMode.exitPlan("Refactor AuthModule...");  // 保存+批准

// 5阶段流程: UNDERSTAND → DESIGN → REVIEW → FINAL_PLAN → EXIT
// 支持并行Explore/Plan子Agent、deviation检测+重规划
```

### 插件扩展

```json
// ~/.mocha/plugins/my-plugin/plugin.json
{
  "name": "my-plugin",
  "version": "1.0",
  "extensions": [
    {"type": "TOOL", "className": "com.example.MyTool", "priority": 10},
    {"type": "PERCEPTOR", "className": "com.example.MyPerceptor", "priority": 5}
  ]
}
```

```java
// 自动发现 ~/.mocha/plugins/*/plugin.json
var bootstrap = AgentBootstrap.init(llm);

// 将最高优先级扩展注入Agent配置
var config = new MochaAgent.Config();
bootstrap.pluginLoader().applyExtensions(config);
var agent = MochaAgent.create(config);
```

## API 指南

### 核心接口

| 接口 | 位置 | 说明 |
|------|------|------|
| `Agent<I,O>` | `agent/Agent.java` | 统一抽象 — execute/executeAsync/函数式组合 |
| `AgenticLoop<I,O>` | `agent/react/AgenticLoop.java` | 执行范式 — run/step |
| `LLM` | `llm/LLM.java` | LLM抽象 — complete/completeAsync/stream |
| `Tool` | `tool/Tool.java` | 工具抽象 — call/getName/getDescription/安全级别 |
| `ExtensionPoint<T>` | `plugin/ExtensionPoint.java` | 插件扩展点 — 9种类型 |
| `Perceptor<I,O>` | `perception/Perceptor.java` | 感知器 — perceive/perceiveAsync/filter |
| `Reasoner` | `reasoning/Reasoner.java` | 推理器 — reason/setStrategy |
| `Planner<T>` | `plan/Planner.java` | 规划器 — generatePlan/replan |
| `Evaluator` | `evaluation/Evaluator.java` | 评估器 — evaluate |
| `Learner<I,O>` | `learn/Learner.java` | 学习器 — learn |
| `MemoryStore` | `memory/MemoryStore.java` | 记忆存储 — store/search/clear |

### LLM 提供者

| Provider | Class | 构造 |
|----------|-------|------|
| OpenAI | `OpenAILLM` | `OpenAILLM.builder().apiKey(key).build()` |
| Anthropic | `AnthropicLLM` | `AnthropicLLM.builder().apiKey(key).build()` |
| DeepSeek | `DeepSeekLLM` | `DeepSeekLLM.create()` |
| Groq | `OpenAICompatibleLLM` | compatibility builder |
| Ollama | `OpenAICompatibleLLM.forOllama("llama3")` | 本地 |
| 自动检测 | `LLMFactory.create()` | 按API_KEY env自动选择 |

### 配置对象

```java
// 完整配置
var config = new MochaAgent.Config();
config.name = "my-agent";
config.agentType = Config.AgentType.TOOL_CALLING;
config.llm = llm;
config.maxSteps = 20;
config.toolRegistry = bootstrap.toolRegistry();
config.thinkingConfig = ThinkingConfig.adaptive();
config.effortLevel = EffortLevel.HIGH;
config.reasoner = new DefaultReasoner(llm);
config.planner = new DynamicPlanner<>(new AdaptivePlanner(llm));
config.loop = new ReflexionLoop<>(null, ReflectionEngine.noop());

var agent = MochaAgent.create(config);
```

### 推理相关

```java
// ThinkingConfig
ThinkingConfig.adaptive();            // 模型自行决定
ThinkingConfig.enabled(4096);         // 固定预算
ThinkingConfig.disabled();            // 不思考
ThinkingConfig.resolveForModel(id);   // 按模型自动选择

// EffortLevel
EffortLevel.LOW / MEDIUM / HIGH / MAX;
EffortLevel.resolve(envVar, setting, modelId);
EffortLevel.detectKeywordBoost(input, current);  // "ultrathink"→HIGH

// RecoveryStateMachine
recovery.onPromptTooLong();    // → compactContext()
recovery.onMaxOutputTokens();  // → overrideMaxTokens(64000)
recovery.onModelError(name, statusCode);  // → fallbackModel()
recovery.completeTurn();       // 成功→重置计数器
```

### CLI

```bash
mocha                        # 交互式REPL
mocha --model claude-sonnet  # 指定模型
mocha --temperature 0.3      # 温度
mocha mcp serve              # MCP服务器
mocha plugin                 # 插件管理
mocha doctor                 # 诊断
mocha update                 # 版本检查

# REPL命令
> /help         # 帮助
> /model        # 模型信息
> /cost         # 会话成本
> /clear        # 清空上下文
> /compact      # 压缩上下文
> /plan         # 计划模式
> /exitplan     # 退出计划
> /diff         # 文件变更
> /status       # Agent状态
> /tools        # 工具列表
```

### 上下文系统

```java
// LayeredContextBuilder — 3层上下文+记忆化
contextBuilder.buildFullContext(staticPrompt, dynamicPrompt);
contextBuilder.buildUserContext();     // CLAUDE.md + date
contextBuilder.getSystemContext();     // git + platform + model
contextBuilder.invalidateCache();      // 分支变更/午夜跨天后刷新

// PerceptionObserver — 每步环境感知
observer.observe(task);               // 初始感知
observer.observeAction(result);       // 每步增量
observer.buildEnrichedContext();       // 注入LLM消息
observer.detectChanges();             // 环境变化检测
observer.prefetchAsync(input);        // 异步预取(I/O与LLM调用重叠)
```

### 构建与测试

```bash
mvn compile                        # 编译(含examples)
mvn test                           # 208 tests
mvn spotbugs:check                 # bytecode bug检测
mvn pmd:check                      # 静态分析
```

### 项目统计

| Metric | Value |
|--------|-------|
| 源文件 | 277 |
| 测试文件 | 31 |
| 测试用例 | 208 |
| 生产活跃类 | 211 |
| 编译 | PASS (0 errors) |

### License

Apache License 2.0
