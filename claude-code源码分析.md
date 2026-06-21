# Claude Code 源码参考文档

> 基于 `E:/ai-work/claude-code/src/` 完整遍历分析。1884 个 TypeScript 源文件，覆盖全部模块、接口定义、核心流程图。

---

## 一、总体架构图

```
┌──────────────────────────────────────────────────────────────────┐
│                      用户输入层                                   │
│  TextInput → messageQueueManager (优先级队列: now > next > later) │
└──────────────────────────────┬───────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────┐
│                     QueryEngine.submitMessage()                   │
│  构建系统提示词 → 组装工具池 → 处理斜杠命令 → 调用 query()       │
└──────────────────────────────┬───────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────┐
│                      queryLoop() — while(true)                    │
│  ┌──────────┐  ┌───────────┐  ┌───────────┐  ┌───────────────┐  │
│  │ 记忆预取 │→│ 微压缩优化 │→│ callModel  │→│ 流式处理响应  │  │
│  │ 技能发现 │  │ 自动压缩   │  │ (Anthropic│  │ tool_use收集  │  │
│  │ 附件注入 │  │ 上下文折叠 │  │  API)     │  │ 思维块捕获   │  │
│  └──────────┘  └───────────┘  └───────────┘  └───────┬───────┘  │
│                                                       │          │
│                    ┌──────────────────────────────────┘          │
│                    ▼                                             │
│         ┌──────────────────┐    ┌──────────────────┐            │
│         │StreamingToolExecutor│  │  runTools()      │            │
│         │(流式并发执行)       │  │  (批量分区执行)   │            │
│         └────────┬─────────┘    └────────┬─────────┘            │
│                  │                       │                       │
│                  ▼                       ▼                       │
│         ┌──────────────────────────────────────┐                │
│         │     runToolUse() — 单工具执行管道     │                │
│         │  Zod验证→权限检查→Hook→执行→结果映射  │                │
│         └──────────────────────────────────────┘                │
│                  │                                               │
│                  ▼                                               │
│         有工具结果 → continue(下一轮)                             │
│         无工具结果 → break(终止)                                  │
└──────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────┐
│              横切系统 (并行运行，不阻塞主循环)                     │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────────┐   │
│  │ 压缩系统 │ │ 记忆系统 │ │ 权限系统 │ │ 桥接层 (Bridge)  │   │
│  │ 5层策略  │ │ 4层记忆  │ │ 6种模式  │ │ v1 env+v2 envless│   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────────────┘   │
└──────────────────────────────────────────────────────────────────┘
```

---

## 二、核心类型与接口定义

### 2.1 Tool (工具基类)

**文件**: `src/Tool.ts` (行 362-695)

```typescript
type Tool<Input, Output, P> = {
  name: string
  aliases?: string[]
  inputSchema: Input           // Zod schema
  inputJSONSchema?: object     // 用于 MCP 工具
  call(input, context, canUseTool, parentMsg, onProgress): Promise<ToolResult<Output>>
  isConcurrencySafe(input): boolean     // 可否与其它工具并发
  isEnabled(): boolean
  isReadOnly(input): boolean
  isDestructive?(input): boolean
  checkPermissions(input, context): Promise<PermissionResult>
  mapToolResultToToolResultBlockParam(output, toolUseID): ToolResultBlockParam
  renderToolUseMessage(...): ReactNode
  renderToolResultMessage?(...): ReactNode
  renderToolUseProgressMessage?(...): ReactNode
  maxResultSizeChars: number
  prompt(options): string              // 动态生成提示词
  description(input, options): string
  userFacingName(input): string
}
```

**工厂函数**: `buildTool(def)` (行 783)

```typescript
function buildTool<D extends AnyToolDef>(def: D): BuiltTool<D> {
  return { ...TOOL_DEFAULTS, userFacingName: () => def.name, ...def }
}
// TOOL_DEFAULTS: isEnabled=true, isConcurrencySafe=false, isReadOnly=false, 
//                isDestructive=false, checkPermissions=allow
```

### 2.2 Message (消息类型体系)

**文件**: `src/types/message.ts` (用于 60+ 文件，编译产物存在于 types/message.js)

```typescript
// 联合类型
type Message = UserMessage | AssistantMessage | SystemMessage | 
              AttachmentMessage | ProgressMessage | TombstoneMessage |
              ToolUseSummaryMessage | StreamEvent | RequestStartEvent

// UserMessage
type UserMessage = {
  type: 'user'
  message: { role: 'user', content: string | ContentBlockParam[] }
  uuid: UUID
  timestamp: string (ISO)
  toolUseResult?: unknown
  isMeta?: boolean           // 对模型可见但对UI不可见
  isVirtual?: boolean        // 对UI可见但不发送到API
  isCompactSummary?: boolean
  origin?: MessageOrigin     // 'human'|'channel'|'coordinator'|'task-notification'
}

// AssistantMessage  
type AssistantMessage = {
  type: 'assistant'
  uuid: UUID
  message: {
    id: UUID
    model: string
    role: 'assistant'
    content: ContentBlock[]  // text | tool_use | thinking | redacted_thinking
    usage: BetaUsage
    stop_reason: string
  }
  apiError?: string           // 'max_output_tokens' etc
  error?: SDKAssistantMessageError
  isApiErrorMessage?: boolean
}
```

### 2.3 Task (任务抽象)

**文件**: `src/Task.ts`

```typescript
type TaskType = 'local_bash' | 'local_agent' | 'remote_agent' | 
                'in_process_teammate' | 'local_workflow' | 'monitor_mcp' | 'dream'
type TaskStatus = 'pending' | 'running' | 'completed' | 'failed' | 'killed'

type TaskStateBase = {
  id: string          // 9-char hex, 前缀: b/a/r/t/w/m/d
  type: TaskType
  status: TaskStatus
  description: string
  toolUseId: string
  outputFile: string
  startTime: number
  endTime?: number
  notified: boolean
}
```

### 2.4 QueryParams (查询参数)

**文件**: `src/query.ts` (行 195)

```typescript
type QueryParams = {
  messages: Message[]
  systemPrompt: SystemPrompt
  userContext: { [k: string]: string }    // CLAUDE.md 等
  systemContext: { [k: string]: string }  // git status 等
  canUseTool: CanUseToolFn
  toolUseContext: ToolUseContext
  fallbackModel?: string
  querySource: QuerySource
  maxOutputTokensOverride?: number
  maxTurns?: number
  taskBudget?: { total: number }
}
```

### 2.5 ThinkingConfig (思维配置)

**文件**: `src/utils/thinking.ts`

```typescript
type ThinkingConfig =
  | { type: 'adaptive' }                         // 模型自主决定
  | { type: 'enabled'; budgetTokens: number }    // 固定预算
  | { type: 'disabled' }                         // 禁用

// Gates
function modelSupportsAdaptiveThinking(model): boolean  // Claude 4.6+
function modelSupportsThinking(model): boolean           // Claude 4+ (1P/Foundry)
function shouldEnableThinkingByDefault(): boolean
```

### 2.6 PermissionMode (权限模式)

**文件**: `src/utils/permissions/PermissionMode.ts`

```typescript
type PermissionMode = 
  | 'default'            // 交互式权限对话框
  | 'acceptEdits'        // 自动允许文件编辑
  | 'bypassPermissions'  // 自动允许一切
  | 'plan'              // 计划模式(只读)
  | 'dontAsk'           // 自动拒绝
  | 'auto'              // AI分类器决定
```

### 2.7 AppState (UI 全局状态)

**文件**: `src/state/AppStateStore.ts` (~570 行)

70+ 字段，核心类别：
- `settings: SettingsJson` — 用户设置
- `toolPermissionContext: ToolPermissionContext` — 权限上下文
- `tools: Tool[]` — 可用工具列表
- `mcp: { clients, tools }` — MCP 连接和工具
- `plugins: { enabled, disabled, errors, installationStatus }`
- `tasks: TaskState[]` — 后台任务列表
- `notifications: Notification[]` — 通知
- `agentDefinitions: { activeAgents, allowedAgentTypes }`
- `fileHistory: FileHistoryState` — 文件历史
- `modelSettings: { mainLoopModel, ... }`
- `fastMode: FastModeState`

### 2.8 MemoryEntry (持久记忆)

**文件**: `src/memdir/memoryTypes.ts`

```typescript
type MemoryType = 'user' | 'feedback' | 'project' | 'reference'

// 前端格式:
// ---
// name: <title>
// description: <one line>
// type: user|feedback|project|reference
// ---
// <content>
```

---

## 三、主对话循环流程图

### 3.1 queryLoop() 全流程

```
queryLoop(params: QueryParams): AsyncGenerator<StreamEvent|Message|..., Terminal>

while (true):
  ┌─────────────────────────────────────────┐
  │ ① 预取阶段                               │
  │   · startRelevantMemoryPrefetch()        │  [attachments.ts:2361]
  │   · startSkillDiscoveryPrefetch()        │  [query.ts:336]
  ├─────────────────────────────────────────┤
  │ ② 上下文准备                             │
  │   · getMessagesAfterCompactBoundary()    │  跳过压缩边界前的消息
  │   · applyToolResultBudget()             │  工具结果截断
  │   · snipCompactIfNeeded()               │  [snipCompact.ts]
  │   · microcompactMessages()              │  清除旧工具结果 [microCompact.ts]
  │   · contextCollapse.apply()             │  上下文折叠
  │   · autoCompactIfNeeded()               │  自动压缩 [autoCompact.ts]
  ├─────────────────────────────────────────┤
  │ ③ API 调用                               │
  │   for await (msg of deps.callModel({     │  [claude.ts]
  │     messages, systemPrompt,             │
  │     thinkingConfig, tools, ...           │
  │   })):
  │     if msg.type === 'assistant':
  │       → collect tool_use blocks          │
  │       → capture thinking blocks          │
  │       → streamingToolExecutor.addTool()  │
  │     if msg.type === 'tombstone':         │
  │       → remove orphaned messages         │
  ├─────────────────────────────────────────┤
  │ ④ 错误恢复                               │
  │   · streamingFallback: 切换到回退模型    │
  │   · maxOutputTokens: 注入续写提示        │
  │     (最多重试 MAX_RECOVERY_LIMIT=3次)    │
  │   · promptTooLong: reactiveCompact       │
  ├─────────────────────────────────────────┤
  │ ⑤ 工具执行                               │
  │   if streamingToolExecutor active:       │
  │     getCompletedResults()  // 流中完成   │
  │     getRemainingResults()  // 流后完成   │
  │   else:                                  │
  │     runTools() → partitionToolCalls()    │
  │       → runToolsConcurrently(concurrent) │
  │       → runToolsSerially(non-concurrent) │
  ├─────────────────────────────────────────┤
  │ ⑥ 附件注入                               │
  │   · pendingMemoryPrefetch → 注入记忆附件 │
  │   · pendingSkillPrefetch → 注入技能附件  │
  │   · getAttachmentMessages() → 注入新附件 │
  ├─────────────────────────────────────────┤
  │ ⑦ 停止检查                               │
  │   · stop hook → handleStopHooks()        │
  │   · executeExtractMemories()             │  后台记忆提取
  │   · token budget check → continue/break  │
  ├─────────────────────────────────────────┤
  │ ⑧ 决定                                   │
  │   · hasToolResults → continue (下一轮)   │
  │   · else → return { reason: 'stop' }     │
  └─────────────────────────────────────────┘
```

### 3.2 工具执行详细流程

```
工具调用 -> runToolUse(toolUse, assistantMsg, canUseTool, toolUseContext)

  ┌──────────────────────────────────────┐
  │ 1. 工具查找                          │
  │    findToolByName(tools, name)       │
  │    → fallback aliases                │
  ├──────────────────────────────────────┤
  │ 2. 中止检查                          │
  │    signal.aborted → CANCEL_MESSAGE   │
  ├──────────────────────────────────────┤
  │ 3. Zod 验证                          │
  │    tool.inputSchema.safeParse(input) │
  │    → validation error / success      │
  ├──────────────────────────────────────┤
  │ 4. 工具特定验证                      │
  │    tool.validateInput?.(input, ctx)  │
  ├──────────────────────────────────────┤
  │ 5. PreToolUse Hook                   │
  │    runPreToolUseHooks(...)           │
  │    → can modify input/permissions    │
  ├──────────────────────────────────────┤
  │ 6. 权限检查                          │
  │    canUseTool() →                    │
  │    hasPermissionsToUseTool()         │
  │    ├─ 1a-1g: 规则检查                │
  │    ├─ mode=auto → AI分类器           │
  │    ├─ mode=default → 交互式对话框    │
  │    ├─ mode=bypass → allow            │
  │    ├─ mode=plan → allow(read-only)   │
  │    └─ mode=dontAsk → deny            │
  ├──────────────────────────────────────┤
  │ 7. 工具调用                          │
  │    tool.call(input, context,         │
  │              canUseTool, msg,        │
  │              onProgress)             │
  ├──────────────────────────────────────┤
  │ 8. 结果映射                          │
  │    mapToolResultToToolResultBlockParam│
  │    → processToolResultBlock()        │
  │    → maybePersistLargeToolResult()   │
  ├──────────────────────────────────────┤
  │ 9. PostToolUse Hook                  │
  │    runPostToolUseHooks(...)          │
  │    → can modify MCP tool output      │
  ├──────────────────────────────────────┤
  │ 10. Context modifiers                │
  │     tool contextModifier → apply     │
  └──────────────────────────────────────┘
```

### 3.3 权限决策详细流程

```
hasPermissionsToUseTool(tool, input, context)

Step 1: 规则检查 (总是执行)
  ├─ 1a: getDenyRuleForTool()        → deny → 直接拒绝
  ├─ 1b: getAskRuleForTool()         → ask
  ├─ 1c: tool.checkPermissions()     → 工具特定检查
  ├─ 1d: 如果工具拒绝 → deny
  ├─ 1e: tool.requiresUserInteraction() → ask
  ├─ 1f: 内容特定规则匹配 → deny/ask
  └─ 1g: 安全路径检查 (.git/.claude) → 不可绕过

Step 2: 模式检查
  ├─ bypassPermissions → allow
  ├─ plan + isBypassAvailable → allow
  └─ toolAlwaysAllowedRule() → allow

Step 3: Passthrough → ask (with default message)

Step 4: 模式转换 (当结果是 ask 时)
  ├─ dontAsk → deny
  └─ auto / plan+auto:
       ├─ safetyChecks 不通过 → ask/deny
       ├─ acceptEdits 快速路径 → allow
       ├─ isAutoModeAllowlistedTool → allow
       ├─ classifyYoloAction() → AI 分类器决定
       │   └─ XML 两阶段:
       │       Stage 1 (fast): max_tokens=64, stop=['</block>']
       │       Stage 2 (thinking): max_tokens=4096, CoT
       └─ 拒绝过多 → 回退到提示用户
```

---

## 四、工具调用系统

### 4.1 工具注册与组装

```
getAllBaseTools()           [tools.ts:193]
  → 40+ 内置工具 (feature() gates)
  
getTools(permissionContext) [tools.ts:271]
  → 过滤: SIMPLE/Sandbox/REPL/deny规则/isEnabled
  
assembleToolPool(ctx, mcp)  [tools.ts:345]
  → 内置工具 + MCP工具
  → 去重 (内置优先)
  → 字母排序 (缓存稳定)
```

### 4.2 工具 Schema 序列化为 API 格式

```
toolToAPISchema(tool, options) [api.ts:119]

  Zod schema → JSON Schema
  + tool.prompt() → description
  + cache_control (ephemeral)
  + defer_loading (按需加载)
  + strict (结构化输出)
  → BetaToolUnion
```

### 4.3 并发模型

```
StreamingToolExecutor [StreamingToolExecutor.ts]

  canExecuteTool():
    ├─ 无执行中工具 → 立即开始
    ├─ 所有执行中 + 当前 都是 concurrentSafe → 并行开始
    ├─ 有非并发工具在执行 → 等待
    └─ 当前非并发 + 前面有工具 → 等待前面完成

  Bash 错误级联:
    tool errored → siblingAbortController.abort('sibling_error')
    → 所有兄弟子进程被取消

  并发限制: CLAUDE_CODE_MAX_TOOL_USE_CONCURRENCY (默认 10)

runTools() [toolOrchestration.ts]
  partitionToolCalls():
    连续并发安全块 → 一组 (并行执行)
    每个非并发安全块 → 单独一组 (串行执行)
```

### 4.4 工具结果处理

```
normalizeMessagesForAPI() [messages.ts:1989]
  → reorderAttachmentsForAPI()
  → stripVirtualMessages()
  → mergeUserMessages()
  → ensureToolResultPairing()
     ├─ 移除孤立的 tool_result
     ├─ 去重 tool_use blocks
     ├─ 剥离服务端 tool_use
     └─ 插入空内容占位符
```

---

## 五、记忆系统

### 5.1 四层架构

```
┌──────────────────────────────────────────────────┐
│ Layer 1: 持久记忆 (跨会话)                        │
│ ~/.claude/projects/<project>/memory/              │
│   ├── MEMORY.md            ← 索引 (max 200行)     │
│   ├── user_role.md          ← 用户角色            │
│   ├── feedback_testing.md   ← 用户反馈            │
│   ├── project_init.md       ← 项目知识            │
│   └── reference_slack.md    ← 外部引用            │
│                                                    │
│ 类型: user | feedback | project | reference       │
│ 格式: YAML frontmatter + Markdown body            │
│ 安全: 路径遍历保护、符号链接检测                  │
├──────────────────────────────────────────────────┤
│ Layer 2: 会话记忆 (当前会话)                      │
│ ~/.claude/session-memory/<sessionId>.md           │
│                                                    │
│ 9 个部分:                                         │
│ Session Title, Current State, Task Specification, │
│ Files/Functions, Workflow, Errors/Corrections,    │
│ Codebase Docs, Learnings, Key Results, Worklog    │
│                                                    │
│ 触发: 10K token 后初始化, 每 5K token 更新        │
│ 压缩: sessionMemoryCompact 优先于 API 压缩        │
├──────────────────────────────────────────────────┤
│ Layer 3: 自动记忆 (系统提示词驱动)                │
│ buildMemoryLines() → 系统提示词中注入指令         │
│                                                    │
│ 指令内容:                                         │
│ · 何时保存: 发现用户偏好、收到反馈、了解项目背景  │
│ · 何时不保存: code patterns, git history, 临时状态│
│ · 何时访问: 用户引用之前的工作、需要上下文        │
│ · 访问前验证: 文件路径存在、grep函数名            │
├──────────────────────────────────────────────────┤
│ Layer 4: 记忆检索 (注入对话)                      │
│                                                    │
│ 预取流程:                                         │
│ startRelevantMemoryPrefetch() [attachments.ts]    │
│   → findRelevantMemories() [findRelevantMemories] │
│     → scanMemoryFiles() [200 files, 按时间排序]   │
│     → sideQuery(Sonnet) 选择 top 5                │
│     → readMemoriesForSurfacing() [4KB/file]       │
│   → filterDuplicateMemoryAttachments()            │
│     → 去除已读/已注入的                           │
│   → createAttachmentMessage() [注入对话]          │
│                                                    │
│ 后台提取 (写):                                    │
│ executeExtractMemories() [查询循环结束]           │
│   → runForkedAgent() [最多 5 轮]                  │
│     → 写 topic.md + 编辑 MEMORY.md                │
│     → createMemorySavedMessage()                  │
└──────────────────────────────────────────────────┘
```

### 5.2 记忆检索流程图

```
queryLoop():
  │
  ├─① 预取启动 (每回合一次)
  │   using pendingMemoryPrefetch = startRelevantMemoryPrefetch(
  │     messages, toolUseContext
  │   )
  │   → 提取最后用户消息
  │   → 跳过单字提示 (无上下文)
  │   → collectSurfacedMemories() 检查预算 (60KB)
  │   → findRelevantMemories()
  │       ├─ scanMemoryFiles(dir) → 200 files
  │       ├─ formatMemoryManifest()
  │       └─ selectRelevantMemories() → sideQuery(Sonnet) → top 5
  │   → readMemoriesForSurfacing() → max 4KB/file
  │
  │  (并发运行: 模型流式 + 工具执行)
  │
  ├─② 消费点 (工具执行后)
  │   if prefetch.settledAt !== null && !consumed:
  │     filterDuplicateMemoryAttachments(
  │       await pendingMemoryPrefetch.promise,
  │       readFileState
  │     )
  │     for memoryAttachment:
  │       createAttachmentMessage() → yield → 注入对话
  │
  ├─③ 后台提取 (查询循环结束)
  │   executeExtractMemories(context)
  │     → hasMemoryWritesSince(messages, cursor)?
  │         YES → 主 Agent 已写入, 跳过
  │     → runForkedAgent()
  │         extractAutoOnlyPrompt / extractCombinedPrompt
  │         maxTurns: 5
  │         canUseTool: createAutoMemCanUseTool(memoryDir)
  │     → createMemorySavedMessage(memoryPaths) → appendSystemMessage
```

---

## 六、压缩系统

### 6.1 五层策略

| 策略 | 触发时机 | 成本 | 效果 |
|------|---------|------|------|
| **Micro** (microCompact) | 每个 API 请求前 | 低 | 清除旧工具结果 |
| **Time-Based MC** | 间隔 >= 60min | 低 | 缓存失效时清除 |
| **Cached MC** | 每个 API 请求前 | 低 | API `cache_edits` |
| **Auto** (autoCompact) | token 超阈值 | 中 | 会话记忆优先,回退完整压缩 |
| **SessionMemoryCompact** | autoCompact 第一优先 | 低 | 用会话记忆替代 API 调用 |
| **Full** (compactConversation) | 手动/自动 | 高 | Fork Agent 生成摘要 |
| **Reactive** | API 返回 promptTooLong | 高 | 应急压缩 |

### 6.2 AutoCompact 决策

```
shouldAutoCompact(messages, model):
  tokenCount = estimatedTokens()
  threshold = contextWindow - 20K(output) - 13K(buffer)
  
  if tokenCount > threshold:
    circuit_breaker:
      max 3 连续失败 → 停止
    优先: trySessionMemoryCompaction()
    回退: compactConversation()
```

---

## 七、推理/思维系统

### 7.1 ThinkingConfig 生命周期

```
appState → toolUseContext.options.thinkingConfig
  → deps.callModel({ thinkingConfig, ... })  [query.ts:662]
  → API stream yields ThinkingBlock
  → handleMessageFromStream() → onStreamingThinking()
  → normalizeMessages() → 拆分为独立 NormalizedAssistantMessage
  → normalizeMessagesForAPI() → 合并且确保thinking-tool_use配对
  → 压缩 → buildPostCompactMessages() 清除历史thinking块
```

### 7.2 "思维三法则" (query.ts:151-163)

1. 含 `thinking` 或 `redacted_thinking` 的消息必须在 `max_thinking_length > 0` 的查询中
2. thinking 块不能是消息中的最后一个块
3. thinking 块必须在助手轨迹期间保留 (单次对话, 或者包含 tool_use 的对话链)

---

## 八、规划/计划模式

### 8.1 计划模式生命周期

```
用户输入 /plan 或模型调用 EnterPlanMode
  → EnterPlanModeTool.call()
    → handlePlanModeTransition(prevMode, 'plan')    [bootstrap/state.ts]
    → prepareContextForPlanMode()                    [auto模式激活]
    → setAppState({ mode: 'plan' })                  [权限切换到只读]
    → 系统提示词加入 plan_mode 指令

模型探索, 写入计划文件:
  → getPlanFilePath() → <plansDir>/<slug>.md
  → FileWriteTool 写入计划内容

模型调用 ExitPlanMode:
  → ExitPlanModeV2Tool.call()
    → getPlan() 从磁盘读取
    → Teammate 需要 Leader 审批:
        writeToMailbox('team-lead', plan_approval_request)
        set awaitingPlanApproval = true
    → 非 Teammate:
        restoreMode = prePlanMode ?? 'default'
        setNeedsPlanModeExitAttachment(true)
    → mapToolResultToToolResultBlockParam() 嵌入计划

模型看到批准的计划 → 开始实施
```

---

## 九、消息传递系统

### 9.1 内部消息流

```
UserMessage → normalizeMessages() [messages.ts:740]
  → 多块拆分为单块 (每 content block 一条 NormalizedMessage)
  → String content → [{ type: 'text', text: content }]
  
normalizeMessagesForAPI() [messages.ts:1989]
  → reorderAttachmentsForAPI()
  → stripVirtualMessages()
  → mergeUserMessages()
  → ensureToolResultPairing()

API 响应流:
  → handleMessageFromStream() [messages.ts:2930]
    → onStreamingText / onStreamingThinking / onTombstone
```

### 9.2 SDK 消息转换

```
SDK ↔ Internal:
  toInternalMessages() [mappers.ts]
    SDKUserMessage → UserMessage
    SDKAssistantMessage → AssistantMessage
    SDKCompactBoundaryMessage → SystemMessage
    SDKToolProgressMessage → ProgressMessage

  toSDKMessages() [mappers.ts]
    反向转换: Internal → SDKMessage
    + tool_use_id 注入
    + isSynthetic/isMeta passthrough
```

### 9.3 Bridge 入站消息

```
handleIngressMessage(data, recentPostedUUIDs, recentInboundUUIDs)  [bridgeMessaging.ts:132]

  JSON parse → normalizeControlMessageKeys()
  ├─ control_response → onPermissionResponse()
  ├─ control_request → onControlRequest()
  │   ├─ initialize → 创建会话
  │   ├─ interrupt → 中止
  │   ├─ set_model → 设置模型
  │   ├─ set_permission_mode → 设置权限模式
  │   └─ list_tools → 返回工具列表
  └─ SDKMessage:
      echo detect: recentPostedUUIDs.has(uuid) → skip
      dedup: recentInboundUUIDs.has(uuid) → skip
      → onInboundMessage(msg)
        → convertSDKMessage() [sdkMessageAdapter.ts]
        → enqueue() → query engine

BoundedUUIDSet (FIFO 去重):
  容量: configurable (env-less) / 2000 (v1 bridge)
  淘汰: 最旧的 UUID
```

---

## 十、意图识别

### 10.1 斜杠命令检测

```
isSlashCommand(cmd) [messageQueueManager.ts:541]
  cmd.value.trim().startsWith('/') && !cmd.skipSlashCommands

→ processSlashCommand → 不发送给模型
  skipSlashCommands: true → 发送给模型 (bridge/CCR消息)
```

### 10.2 命令路由

```
getCommands(cwd) [commands.ts:476]
  来源层级:
    skills dir → plugin skills → bundled skills → 
    builtin plugin skills → workflow commands → 
    plugin commands → built-in commands → dynamic skills
  
  findCommand(name, commands) → Command | undefined
```

### 10.3 命令优先级队列

```
enqueue(cmd) / dequeue(filter?)

优先级: 'now'(0) > 'next'(1) > 'later'(2)
  · 用户输入: 'next'
  · 任务通知: 'later' (用户输入优先)
  · 孤儿权限: 'now' (最高)
```

---

## 十一、Bootstrap 全局状态

**文件**: `src/bootstrap/state.ts` (~1759 行)

模块级 STATE 对象，DAG 叶子（无循环依赖），getter/setter 模式。

**关键状态类别**:

| 类别 | 字段 | 访问器 |
|------|------|--------|
| 会话标识 | sessionId, projectRoot, cwd, parentSessionId | getSessionId(), getProjectRoot() |
| 成本追踪 | totalCostUSD, totalAPIDuration, totalToolDuration | getTotalCost(), addCost() |
| 模型设置 | mainLoopModelOverride, initialMainLoopModel | getMainLoopModel(), setModel() |
| 标志 | isInteractive, kairosActive, strictToolResultPairing | isNonInteractiveSession() |
| 遥测 | OTel meter/counter (session, LOC, cost) | — |
| 权限 | sessionBypassPermissionsMode | setSessionBypassPermissions() |
| 每轮 | turnHookDurationMs, turnToolCount | — |
| 压缩 | pendingPostCompaction, invokedSkills | markPostCompaction() |

---

## 十二、关键文件索引 (按模块)

### 核心循环
| 文件 | 行数 | 作用 |
|------|------|------|
| src/query.ts | ~1700 | 主对话循环 queryLoop() |
| src/QueryEngine.ts | ~200 | 会话生命周期管理 |
| src/Task.ts | ~100 | 任务类型/状态/ID生成 |
| src/Tool.ts | ~793 | 工具接口/buldTool工厂/ToolUseContext |
| src/tools.ts | ~370 | 工具注册/组装/过滤 |
| src/commands.ts | ~700 | 80+斜杠命令注册/路由 |
| src/context.ts | ~150 | git状态+CLAUDE.md上下文 |
| src/history.ts | ~200 | 提示历史持久化 |
| src/cost-tracker.ts | ~ | Token/成本追踪 |

### 消息系统
| 文件 | 行数 | 作用 |
|------|------|------|
| src/utils/messages.ts | ~5512 | 消息工厂/归一化/API转换 |
| src/utils/messagePredicates.ts | ~8 | isHumanTurn() 类型守卫 |
| src/utils/messageQueueManager.ts | ~600 | 优先级命令队列 |
| src/types/message.ts | — | Message 联合类型 (编译产物) |
| src/constants/messages.ts | ~5 | NO_CONTENT_MESSAGE |

### 压缩系统
| 文件 | 行数 | 作用 |
|------|------|------|
| src/services/compact/compact.ts | ~1706 | 完整压缩+部分压缩 |
| src/services/compact/autoCompact.ts | ~300 | 自动压缩(阈值+熔断) |
| src/services/compact/microCompact.ts | ~400 | 微压缩(3种子策略) |
| src/services/compact/sessionMemoryCompact.ts | ~530 | 会话记忆压缩 |
| src/services/compact/grouping.ts | ~62 | API轮次分组 |
| src/services/compact/prompt.ts | ~200 | 压缩提示词模板 |

### 记忆系统
| 文件 | 行数 | 作用 |
|------|------|------|
| src/memdir/paths.ts | ~290 | 路径解析/启用门控 |
| src/memdir/memoryTypes.ts | ~280 | 4类型分类法 |
| src/memdir/memoryScan.ts | ~120 | 目录扫描/清单格式化 |
| src/memdir/findRelevantMemories.ts | ~130 | Sonnet 相关性选择 |
| src/memdir/memdir.ts | ~530 | MEMORY.md管理/提示词构建 |
| src/memdir/memoryAge.ts | ~60 | 陈旧性警告 |
| src/memdir/teamMemPaths.ts | ~290 | 团队记忆路径/安全 |
| src/services/extractMemories/ | ~600 | 后台记忆提取Agent |
| src/services/SessionMemory/ | ~700 | 会话记忆Hook/Agent |
| src/utils/attachments.ts | ~2600 | 预取/注入/去重 |
| src/utils/sideQuery.ts | ~110 | 轻量模型API调用 |

### 桥接/传输
| 文件 | 行数 | 作用 |
|------|------|------|
| src/bridge/bridgeMessaging.ts | ~462 | 入站消息路由/去重 |
| src/bridge/replBridge.ts | ~2400 | v1 桥接核心 |
| src/bridge/remoteBridgeCore.ts | ~900 | v2 env-less桥接 |
| src/bridge/replBridgeTransport.ts | ~200 | 传输抽象层 |
| src/bridge/types.ts | ~263 | 桥接协议类型 |
| src/cli/print.ts | ~213KB | SDK模式执行循环 |
| src/cli/transports/HybridTransport.ts | ~ | WS读+HTTP写 |
| src/cli/transports/SSETransport.ts | ~ | SSE自动重连 |
| src/cli/transports/ccrClient.ts | ~34KB | CCR v2客户端 |

### 服务层
| 文件 | 行数 | 作用 |
|------|------|------|
| src/services/mcp/client.ts | ~119KB | MCP客户端核心 |
| src/services/mcp/auth.ts | ~89KB | OAuth 2.0 + PKCE |
| src/services/mcp/config.ts | ~51KB | 多来源配置加载 |
| src/services/api/client.ts | ~390 | 多后端工厂(5种) |
| src/services/api/errors.ts | ~1208 | 错误分类/用户消息 |
| src/services/api/withRetry.ts | ~200 | 指数退避重试 |
| src/services/tools/StreamingToolExecutor.ts | ~531 | 流式并发执行 |
| src/services/tools/toolOrchestration.ts | ~189 | 批量分区执行 |
| src/services/lsp/LSPClient.ts | ~448 | LSP客户端(vscode-jsonrpc) |
| src/services/plugins/pluginOperations.ts | ~36KB | 插件CRUD |

### UI 层
| 文件 | 行数 | 作用 |
|------|------|------|
| src/ink/ink.tsx | ~252KB | 自维护Ink渲染器 |
| src/components/Message.tsx | ~627 | 消息渲染(所有类型) |
| src/components/Messages.tsx | ~347KB | 消息列表容器 |
| src/components/permissions/PermissionPrompt.tsx | ~37KB | 权限对话框 |
| src/components/permissions/PermissionRequest.tsx | ~34KB | 权限请求组件 |

### 状态/设置/技能
| 文件 | 行数 | 作用 |
|------|------|------|
| src/bootstrap/state.ts | ~1759 | 全局会话状态 |
| src/state/AppStateStore.ts | ~570 | UI状态 (70+字段) |
| src/utils/settings/types.ts | ~43KB | SettingsJson Zod Schema |
| src/constants/prompts.ts | ~54KB | 完整系统提示词 |
| src/skills/loadSkillsDir.ts | ~500 | 技能加载(多来源+动态) |
| src/skills/bundledSkills.ts | ~100 | 捆绑技能注册 |
| src/keybindings/defaultBindings.ts | ~12KB | 平台默认快捷键 |
| src/utils/thinking.ts | ~150 | ThinkingConfig |
| src/utils/permissions/permissions.ts | ~1200 | 权限检查完整管道 |

---

## 十三、模式转换矩阵

| 从→到 | default | acceptEdits | bypass | plan | dontAsk | auto |
|--------|---------|-------------|--------|------|---------|------|
| **default** | — | /accept-edits | /bypass | /plan | /dont-ask | — |
| **acceptEdits** | 退出 | — | /bypass | /plan | /dont-ask | — |
| **plan** | ExitPlanMode | — | — | — | — | — |
| **auto** | 电路断开 | — | — | EnterPlanMode | — | — |

工具行为：
- **plan**: 只读工具可用, 写/编辑工具阻止
- **auto**: 分类器评估每个工具调用, 安全工具绕过
- **default**: 每工具权限对话框
- **bypass**: 所有工具允许
- **acceptEdits**: CWD内写工具自动允许
- **dontAsk**: 需提示的工具自动拒绝

---

## 十四、完整目录与文件统计

### 源码规模

**1900 个源文件，160+ 目录**。按模块分布：

| 模块 | 文件数 | 说明 |
|------|--------|------|
| **根文件** | 17 | query.ts, QueryEngine.ts, Tool.ts, Task.ts, tools.ts, commands.ts, context.ts, history.ts, cost-tracker.ts, setup.ts 等 |
| **utils/** | ~570 | 最大区域，45+ 子目录：messages(5512行), permissions, model, settings, hooks, git, memory, sandbox, skills, bash, swarm 等 |
| **components/** | ~360 | 35+ 子目录：Message/Messages 渲染, permissions 对话框(30+), agents, mcp, memory, sandbox, teams, tasks, shell, design-system 等 |
| **tools/** | ~130 | 38 个工具子目录，每个含主实现+UI+prompt+常量 |
| **services/** | ~130 | 20+ 子目录：compact(压缩), mcp(MCP协议), api(HTTP层), lsp(语言服务), SessionMemory, extractMemories, analytics, plugins 等 |
| **commands/** | ~110 | 50+ 内置斜杠命令子目录：help/clear/compact/config/review/plan/agents/doctor 等 |
| **hooks/** | ~110 | React hooks：useCanUseTool, toolPermission(权限管道), notifs, 等 |
| **ink/** | ~90 | 自维护终端 UI 框架(React for terminal) |
| **bridge/** | ~35 | IDE/Web 桥接：双协议(v1 env-based, v2 env-less) |
| **types/** | ~12 | 类型定义 + 生成的 protobuf 类型 |
| **constants/** | ~20 | prompts.ts(54KB系统提示词), product, oauth, outputStyles, tools, betas, messages 等 |
| **skills/** | 20 | 技能框架 + bundled/目录下 19 个内置技能 |
| **keybindings/** | 16 | 键盘快捷键系统：默认绑定/匹配/解析/用户加载/验证 |
| **entrypoints/** | 8 | CLI, MCP, SDK 入口点和类型生成 |
| **cli/** | 16 | CLI 传输层(5种传输协议) + 子命令处理器(auth, mcp, agents, plugins) |
| **migrations/** | 11 | 模型迁移脚本(Sonnet 4.5→4.6, Opus 1M 等) |
| **memdir/** | 8 | 记忆目录：paths, scan, findRelevant, types, teamMem, memdir |
| **state/** | 6 | AppStateStore(~570行), store, onChange, selectors |
| **bootstrap/** | 1 | state.ts(~1759行)，全局会话状态单例 |
| **remote/** | 4 | 远程会话管理(WebSocket, CCR) |
| **vim/** | 5 | Vim 模式(motions, operators, textObjects, state machine) |
| **screens/** | 3 | REPL, Doctor, Resume 终端屏幕 |
| **server/** | 3 | 本地 HTTP/WS 服务器(DirectConnect) |
| **plugins/** | 2 | 内置插件注册+初始化 |
| **其他** | ~10 | voice, outputStyles, moreright, native-ts(3), upstreamproxy, schemas |

### 核心文件 TOP 30 (按行数)

| 文件 | 行数 | 模块 |
|------|------|------|
| utils/messages.ts | 5512 | 消息工厂/归一化 |
| constants/prompts.ts | ~3500 | 系统提示词 |
| ink/ink.tsx | ~3400 | 终端渲染引擎 |
| services/mcp/client.ts | ~3200 | MCP 客户端 |
| bootstrap/state.ts | 1759 | 全局状态 |
| services/compact/compact.ts | 1706 | 完整压缩 |
| query.ts | ~1700 | 主循环 |
| services/api/errors.ts | 1208 | 错误分类 |
| utils/permissions/permissions.ts | ~1200 | 权限管道 |
| bridge/replBridge.ts | ~2400 | v1 桥接 |
| services/mcp/auth.ts | ~2300 | MCP OAuth |
| services/mcp/config.ts | ~1300 | MCP 配置 |
| utils/attachments.ts | ~2600 | 附件+记忆预取 |
| cli/print.ts | ~2500 | SDK 执行循环 |
| bridge/remoteBridgeCore.ts | 900 | v2 桥接 |
| Tool.ts | 793 | 工具接口 |
| commands.ts | ~700 | 命令注册 |
| utils/settings/types.ts | ~1100 | SettingsJson Schema |
| QueryEngine.ts | ~200 | 查询引擎 |
| services/tools/StreamingToolExecutor.ts | 531 | 流式执行器 |
| services/compact/autoCompact.ts | 300 | 自动压缩 |
| services/compact/microCompact.ts | 400 | 微压缩 |
| services/lsp/LSPClient.ts | 448 | LSP 客户端 |
| Task.ts | ~100 | 任务抽象 |
| state/AppStateStore.ts | 570 | UI 状态 |
