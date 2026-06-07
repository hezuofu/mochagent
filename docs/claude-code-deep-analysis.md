# Claude Code 深度架构分析

> 分析日期: 2026-06-07
> 分析对象: Claude Code TypeScript 源码 (2026-03-31 公开快照)
> 规模: ~1,900 文件, 512,000+ 行 TypeScript
> 运行时: Bun + React/Ink 终端 UI
> 分析目的: 为 mochagent (Java) 的架构设计提供参考

---

## 目录

1. [整体架构概览](#1-整体架构概览)
2. [QueryEngine — 核心 Agent 引擎](#2-queryengine--核心-agent-引擎)
3. [Query Loop — 主查询循环](#3-query-loop--主查询循环)
4. [Session & Transcript 存储](#4-session--transcript-存储)
5. [Memory System — 记忆系统](#5-memory-system--记忆系统)
6. [Context 注入体系](#6-context-注入体系)
7. [Compaction System — 压缩系统](#7-compaction-system--压缩系统)
8. [Tool System — 工具系统](#8-tool-system--工具系统)
9. [Task System — 后台任务系统](#9-task-system--后台任务系统)
10. [AppState — 应用状态管理](#10-appstate--应用状态管理)
11. [Permission System — 权限系统](#11-permission-system--权限系统)
12. [Bootstrap 全局状态](#12-bootstrap-全局状态)
13. [Message 类型体系](#13-message-类型体系)
14. [Plugin System — 插件系统](#14-plugin-system--插件系统)
15. [Agent/Subagent — 多代理系统](#15-agentsubagent--多代理系统)
16. [关键数据结构总览](#16-关键数据结构总览)
17. [与 hermes-agent 的架构对比](#17-与-hermes-agent-的架构对比)
18. [对 mochagent 的架构启示](#18-对-mochagent-的架构启示)

---

## 1. 整体架构概览

### 1.1 分层架构

```
┌──────────────────────────────────────────────────────────────────┐
│                     ENTRYPOINTS (入口层)                          │
│  cli/  SDK/  REPL/  bridge/  remote/  voice/                     │
├──────────────────────────────────────────────────────────────────┤
│                     UI LAYER (React/Ink)                          │
│  components/  screens/  ink/  interactiveHelpers.tsx             │
│  main.tsx (803KB — 主 REPL UI 组件)                              │
├──────────────────────────────────────────────────────────────────┤
│                     CORE AGENT LAYER                              │
│  QueryEngine.ts  ← 核心引擎 (headless/SDK)                       │
│  query.ts        ← 查询循环 (REPL + SDK 共享)                     │
├──────────────────────────────────────────────────────────────────┤
│                     SERVICES LAYER                                │
│  services/api/        ← Anthropic API 调用                       │
│  services/compact/    ← 压缩系统 (micro/macro/reactive/snip)     │
│  services/tools/      ← 工具编排 (StreamingToolExecutor)          │
│  services/mcp/        ← MCP 协议支持                             │
│  services/lsp/        ← LSP 语言服务器                           │
│  services/analytics/  ← 遥测/分析                                │
│  services/plugins/    ← 插件发现与加载                            │
├──────────────────────────────────────────────────────────────────┤
│                     TOOLS LAYER                                    │
│  tools/  (50+ 工具 — 每个是独立目录)                               │
├──────────────────────────────────────────────────────────────────┤
│                     STATE LAYER                                    │
│  state/AppState.tsx    ← React State (UI 状态)                    │
│  bootstrap/state.ts    ← 全局非 UI 状态 (cwd, sessionId, ...)     │
│  memdir/               ← Memory 目录 (MEMORY.md + auto-memory)    │
├──────────────────────────────────────────────────────────────────┤
│                     UTILITY LAYER                                  │
│  utils/  (200+ utility 模块)                                      │
└──────────────────────────────────────────────────────────────────┘
```

### 1.2 核心设计原则

1. **QueryEngine 是核心** — 一个 QueryEngine 实例对应一次对话，持久化状态跨 turn 保持
2. **query() generator 函数** — 主循环是 `AsyncGenerator`，所有中间消息通过 `yield` 输出
3. **Branded Types** — `SessionId` 和 `AgentId` 是 branded string types，编译时区分
4. **Feature Flags (DCE)** — 使用 `feature()` 进行编译时 Dead Code Elimination
5. **React/Ink UI** — 终端 UI 使用 React + Ink 框架，AppState 通过 React Context + useSyncExternalStore 管理
6. **Generic Tool Framework** — 每个工具是一个独立目录（含 prompt.ts + 实现），通过 Zod schema 验证输入

### 1.3 目录结构

| 目录 | 职责 | 关键文件 |
|------|------|---------|
| `src/` | 根模块 | QueryEngine.ts, query.ts, Tool.ts, Task.ts, context.ts, commands.ts |
| `src/types/` | 类型定义 | ids.ts (branded), logs.ts (transcript), permissions.ts, hooks.ts |
| `src/bootstrap/` | 启动状态 | state.ts (全局单例状态, ~400行) |
| `src/state/` | UI 状态 | AppState.tsx, AppStateStore.ts (~400行 AppState) |
| `src/services/` | 核心服务 | api/, compact/, tools/, mcp/, lsp/, analytics/ |
| `src/tools/` | 工具实现 | 50+ 子目录, 每工具独立 |
| `src/tasks/` | 后台任务 | LocalAgentTask, LocalBashTask, WorkflowTask, ... |
| `src/utils/` | 工具函数 | 200+ 模块 (messages, sessionStorage, config, ...) |
| `src/memdir/` | 记忆系统 | memdir.ts, findRelevantMemories.ts, memoryTypes.ts |
| `src/context/` | 上下文注入 | notifications.ts, mailbox.ts |
| `src/commands/` | 斜杠命令 | clear/, compact/, init/, review/, ... |
| `src/components/` | React 组件 | 终端 UI 组件 |
| `src/screens/` | React 屏幕 | 终端 UI 页面 |
| `src/bridge/` | 远程桥接 | remoteBridge, replBridge, sessionRunner |
| `src/plugins/` | 插件系统 | 插件发现与加载 |
| `src/entrypoints/` | SDK 入口 | agentSdkTypes.ts |
| `src/skills/` | 技能系统 | 技能发现与执行 |
| `src/query/` | 查询子模块 | config.ts, deps.ts, stopHooks.ts, tokenBudget.ts |

---

## 2. QueryEngine — 核心 Agent 引擎

### 2.1 设计

```typescript
// src/QueryEngine.ts — Headless/SDK 路径的核心引擎
export class QueryEngine {
  private config: QueryEngineConfig
  private mutableMessages: Message[]          // 会话消息列表 (可变)
  private abortController: AbortController     // 中断控制
  private permissionDenials: SDKPermissionDenial[]
  private totalUsage: NonNullableUsage         // Token 使用累积
  private readFileState: FileStateCache        // 文件读取缓存 (跨 turn)
  private discoveredSkillNames: Set<string>    // Turn 级技能发现
  private loadedNestedMemoryPaths: Set<string> // 已加载的嵌套记忆路径

  // 生命周期: 一个 QueryEngine = 一次对话
  // 每次 submitMessage() 调用 = 一个新 turn

  async *submitMessage(
    prompt: string | ContentBlockParam[],
    options?: { uuid?: string; isMeta?: boolean },
  ): AsyncGenerator<SDKMessage, void, unknown>
}
```

### 2.2 关键配置 (QueryEngineConfig)

```typescript
type QueryEngineConfig = {
  cwd: string                           // 工作目录
  tools: Tools                          // 工具定义集合
  commands: Command[]                   // 斜杠命令
  mcpClients: MCPServerConnection[]     // MCP 服务器连接
  agents: AgentDefinition[]             // 可用 Agent 定义
  canUseTool: CanUseToolFn              // 工具权限检查函数
  getAppState: () => AppState           // 获取 UI 状态
  setAppState: (f: (prev) => AppState)  // 设置 UI 状态
  initialMessages?: Message[]           // 初始消息 (恢复会话)
  readFileCache: FileStateCache         // 文件读缓存
  customSystemPrompt?: string           // 自定义系统提示词
  appendSystemPrompt?: string           // 追加系统提示词
  userSpecifiedModel?: string           // 用户指定模型
  fallbackModel?: string                // 回退模型
  thinkingConfig?: ThinkingConfig       // thinking 配置
  maxTurns?: number                     // 最大 turn 数
  maxBudgetUsd?: number                 // 最大预算 (USD)
  taskBudget?: { total: number }        // Token 预算
  jsonSchema?: Record<string, unknown>  // 结构化输出 schema
  verbose?: boolean                     // 详细模式
}
```

### 2.3 会话持久化机制

```typescript
// QueryEngine 支持 session persistence:
// 1. 每个 submitMessage 记录到 JSONL transcript
// 2. session ID 在 bootstrap state 中管理
// 3. switchSession() 可以切换会话上下文
// 4. 文件状态通过 FileHistory 记录 (支持 undo)
```

---

## 3. Query Loop — 主查询循环

### 3.1 query() Generator 函数

Claude Code 的核心 agent 循环是一个 `AsyncGenerator`:

```typescript
// src/query.ts
export async function* query(
  params: QueryParams,
): AsyncGenerator<
  StreamEvent | RequestStartEvent | Message | TombstoneMessage | ToolUseSummaryMessage,
  Terminal  // ← 最终返回类型 (Terminal = continue | stop)
>
```

### 3.2 QueryParams (不可变参数)

```typescript
type QueryParams = {
  messages: Message[]                   // 当前消息列表
  systemPrompt: SystemPrompt            // 系统提示词 (SystemPrompt branded type)
  userContext: { [k: string]: string }  // 用户上下文 (CLAUDE.md, currentDate)
  systemContext: { [k: string]: string } // 系统上下文 (gitStatus)
  canUseTool: CanUseToolFn              // 权限函数
  toolUseContext: ToolUseContext        // 工具上下文
  fallbackModel?: string
  querySource: QuerySource              // 'repl' | 'sdk' | 'subagent' | ...
  maxOutputTokensOverride?: number
  maxTurns?: number
  skipCacheWrite?: boolean
  taskBudget?: { total: number }
  deps?: QueryDeps                     // 可注入依赖 (测试)
}
```

### 3.3 查询循环状态 (mutable per-loop)

```typescript
type State = {
  messages: Message[]
  toolUseContext: ToolUseContext
  autoCompactTracking: AutoCompactTrackingState | undefined
  maxOutputTokensRecoveryCount: number      // max_output_tokens 恢复计数
  hasAttemptedReactiveCompact: boolean
  maxOutputTokensOverride: number | undefined
  pendingToolUseSummary: Promise<ToolUseSummaryMessage | null> | undefined
  stopHookActive: boolean | undefined
  turnCount: number
  transition: Continue | undefined          // 上一轮为什么继续
}
```

### 3.4 单次迭代核心流程

```
queryLoop 迭代:
│
├── 1. PRE-ITERATION CHECKS
│   ├── maxTurns 检查 → 返回 stop (如果超限)
│   ├── maxOutputTokensRecoveryCount >= 3 → 返回 stop (恢复限制)
│   └── 检查是否有待处理的 tool_use_summary → await 完成
│
├── 2. BUILD API REQUEST
│   ├── normalizeMessagesForAPI(messages)
│   │   ├── ensureToolResultPairing — 确保每个 tool_use 有对应 tool_result
│   │   │   (Claude Code 独特模式 — 防止 API 400 错误)
│   │   ├── dropThinkingOnlyAndMergeUsers — 处理 thinking-only 消息
│   │   └── 压缩后重构 (compact boundary 处理)
│   ├── prependUserContext(messages, userContext)
│   ├── appendSystemContext(messages, systemContext)
│   └── 构建完整的 API 请求体
│
├── 3. API CALL
│   ├── queryModelWithStreaming() → Anthropic Messages API
│   ├── 流式响应处理:
│   │   ├── yield 每个 StreamEvent (delta)
│   │   ├── 累积 content blocks (text, tool_use, thinking)
│   │   └── yield RequestStartEvent (SDK 通知)
│   └── 解析最终 AssistantMessage
│
├── 4. POST-STREAM PROCESSING
│   ├── executePostSamplingHooks() — 后采样 hooks
│   ├── COMPACT CHECK (三种):
│   │   ├── AutoCompact: 自动 token 警告检查
│   │   ├── ReactiveCompact: API 返回 max_output_tokens 时
│   │   └── ContextCollapse: 深度压缩 (大上下文)
│   ├── MicroCompact: tool result 内容清除 (节省 token)
│   └── Tool Use Summary: 大量 tool_use 的批处理摘要
│
├── 5. TOOL EXECUTION (如果有 tool_use)
│   ├── 按 tool_use 块分区: 并发安全 / 非并发安全
│   ├── StreamingToolExecutor (流式场景):
│   │   ├── 工具流式到达 → 立即开始执行
│   │   └── concurrency-safe 工具可并行
│   ├── runTools (非流式场景):
│   │   ├── runToolsConcurrently() — 并发安全批次
│   │   └── runToolsSerially() — 非并发安全批次
│   └── applyToolResultBudget() — 工具结果预算管理
│
├── 6. CONTINUATION CHECK
│   ├── 有 tool_use → Continue → 下一个迭代
│   ├── API error → 分类 → 重试/失败
│   ├── stop_reason → stop hook 处理
│   └── 正常 stop → 返回 Terminal.stop
│
└── 7. POST-TURN
    ├── updateUsage() → 累积 token 使用
    └── recordTranscript() → JSONL 持久化
```

### 3.5 ensureToolResultPairing (Claude Code 关键模式)

```typescript
// 发送到 API 前验证每个 tool_use 都有对应的 tool_result
// 无配对时自动修复 (synthetic error tool_result)
function* yieldMissingToolResultBlocks(
  assistantMessages: AssistantMessage[],
  errorMessage: string,
) {
  for (const assistantMessage of assistantMessages) {
    const toolUseBlocks = assistantMessage.message.content.filter(
      content => content.type === 'tool_use',
    )
    for (const toolUse of toolUseBlocks) {
      yield createUserMessage({
        content: [{
          type: 'tool_result',
          content: errorMessage,
          is_error: true,
          tool_use_id: toolUse.id,
        }],
        toolUseResult: errorMessage,
        sourceToolAssistantUUID: assistantMessage.uuid,
      })
    }
  }
}
```

### 3.6 Token Budget 特性

```typescript
// Token Budget: +500k 自动续行
// budget.total → API task_budget
// budget.remaining → 每次迭代计算 cumulative API usage
// checkTokenBudget() → 决定是否自动续行

const budgetTracker = feature('TOKEN_BUDGET') ? createBudgetTracker() : null
// budgetTracker 追踪 output tokens, 达到 budget 时自动 continue
```

---

## 4. Session & Transcript 存储

### 4.1 JSONL Transcript 格式

```
~/.claude/projects/<projectSlug>/
    <sessionId>.jsonl     ← 主对话转录 (JSONL 格式, UUID 链)
    <sessionId>.meta.json ← 会话元数据
```

### 4.2 Transcript Entry 类型体系

```typescript
// src/types/logs.ts — 转录日志条目类型

// 核心转录消息 (isTranscriptMessage 类型守卫):
type TranscriptMessage =
  | UserMessage          // type: 'user'
  | AssistantMessage     // type: 'assistant'
  | AttachmentMessage    // type: 'attachment' — 记忆/上下文附件
  | SystemMessage        // type: 'system' — 系统消息 (压缩边界等)

// 元数据消息 (不参与 parentUuid 链):
type MetadataMessage =
  | SummaryMessage       // type: 'summary' — AI 生成的摘要
  | CustomTitleMessage   // type: 'custom-title' — 用户重命名
  | AiTitleMessage       // type: 'ai-title' — AI 生成标题
  | LastPromptMessage    // type: 'last-prompt' — 最近提示词
  | TagMessage           // type: 'tag' — 标签
  | AgentNameMessage     // type: 'agent-name' — Agent 名称
  | AgentColorMessage    // type: 'agent-color' — Agent 颜色
  | AttributionSnapshotMessage  // type: 'attribution-snapshot'
  | FileHistorySnapshotMessage  // type: 'file-history-snapshot'
  | ContextCollapseCommitEntry  // type: 'context-collapse-commit'
  | ContextCollapseSnapshotEntry // type: 'context-collapse-snapshot'
  | PersistedWorktreeSession     // 持久化 worktree 状态

// UUID 链: 每条消息有 uuid + parentUuid
// Progress messages 不是 transcript messages — 不参与 UUID 链
```

### 4.3 SerializedMessage — 持久化格式

```typescript
type SerializedMessage = Message & {
  cwd: string           // 工作目录
  userType: string      // 'cli' | 'sdk-ts' | 'sdk-py' | ...
  entrypoint?: string   // CLAUDE_CODE_ENTRYPOINT
  sessionId: string     // 会话 ID
  timestamp: string     // ISO 时间戳
  version: string       // Claude Code 版本
  gitBranch?: string    // Git 分支
  slug?: string         // 会话 slug (用于 resume)
}
```

### 4.4 LogOption — 会话搜索/列表对象

```typescript
type LogOption = {
  date: string
  messages: SerializedMessage[]
  fullPath?: string
  value: number
  created: Date
  modified: Date
  firstPrompt: string
  messageCount: number
  fileSize?: number
  isSidechain: boolean       // 是否侧链会话 (subagent/etc)
  isLite?: boolean           // 轻量模式 (消息未加载)
  sessionId?: string
  teamName?: string
  agentName?: string
  agentColor?: string
  agentSetting?: string
  isTeammate?: boolean
  leafUuid?: UUID
  summary?: string
  customTitle?: string
  tag?: string
  fileHistorySnapshots?: FileHistorySnapshot[]
  contextCollapseCommits?: ContextCollapseCommitEntry[]
  worktreeSession?: PersistedWorktreeSession
  contentReplacements?: ContentReplacementRecord[]
  mode?: 'coordinator' | 'normal'
  gitBranch?: string
  projectPath?: string
  prNumber?: number | prUrl?: string | prRepository?: string
}
```

### 4.5 Session ID 体系

```typescript
// src/types/ids.ts — Branded Types
type SessionId = string & { readonly __brand: 'SessionId' }
type AgentId = string & { readonly __brand: 'AgentId' }

// AgentId 格式: `a` + optional `<label>-` + 16 hex chars
// 正则: /^a(?:.+-)?[0-9a-f]{16}$/

// SessionId 在 bootstrap state 中管理
getSessionId(): SessionId
switchSession(newSessionId: SessionId): void
```

### 4.6 会话切换

```typescript
// src/bootstrap/state.ts
switchSession(newSessionId: SessionId): void
// 切换文件历史, 清空会话缓存
// sessionStorage.flushSessionStorage() → 刷新当前会话数据
// fileHistory.switchSession(newSessionId)
```

---

## 5. Memory System — 记忆系统

### 5.1 MEMORY.md 分层

```
~/.claude/projects/<projectSlug>/memory/
    MEMORY.md          ← 入口点文件 (手动写入)
    <topic>.md         ← 主题文件 (Get Memory → 读取)
    auto-memory/       ← 自动记忆 (auto-memory 特性)
      <sessionId>.md
```

### 5.2 入口点限制

```typescript
// src/memdir/memdir.ts
const MAX_ENTRYPOINT_LINES = 200       // 行数上限
const MAX_ENTRYPOINT_BYTES = 25_000    // 字节上限

// truncateEntrypointContent() — 行截断优先，再字节截断
// 超限时追加 WARNING 告诉模型写到主题文件
```

### 5.3 loadMemoryPrompt()

```typescript
// 返回注入系统提示词的记忆文本:
// 1. MEMORY.md 内容 (截断后)
// 2. 自动记忆 (auto-memory)
// 3. 团队记忆 (TEAMMEM feature)
// 4. DIR_EXISTS_GUIDANCE — 告知模型目录已存在, 无需 mkdir

loadMemoryPrompt(): Promise<string>
```

### 5.4 附加记忆文件 (Attachment)

```typescript
// src/utils/attachments.ts
// 记忆可以作为 attachment 消息注入:
createAttachmentMessage({
  type: 'memory',
  content: '...',
  path: 'MEMORY.md'
})
// → 以 attachment 形式参与对话，但不占用主 system prompt 空间
```

---

## 6. Context 注入体系

### 6.1 双层缓存

```typescript
// src/context.ts — 两种 context, memoized

// System Context (会话级缓存):
const getSystemContext = memoize(async (): Promise<{ [k: string]: string }> => {
  return {
    gitStatus,  // 'git status --short' + 'git log --oneline -n5' + branch
    ...(feature('BREAK_CACHE_COMMAND') && { cacheBreaker }),
  }
})

// User Context (会话级缓存):
const getUserContext = memoize(async (): Promise<{ [k: string]: string }> => {
  return {
    claudeMd,   // CLAUDE.md 内容 (从 cwd 向上遍历 git root)
    currentDate, // "Today's date is 2026-06-07"
  }
})
```

### 6.2 CLAUDE.md 发现

```
从 cwd 向上遍历 git root 查找:
  1. CLAUDE.md / .claude.md
  2. CLAUDE.local.md (gitignored, 本地覆盖)
  3. .claude/CLAUDE.md
  4. --add-dir 指定的额外目录中的 CLAUDE.md
```

### 6.3 System Prompt 构建

```typescript
// 系统提示词构建 (在 QueryEngine.submitMessage 中):
const { defaultSystemPrompt, userContext, systemContext } =
  await fetchSystemPromptParts({
    tools,
    mainLoopModel,
    additionalWorkingDirectories,
    mcpClients,
    customSystemPrompt,
    appendSystemPrompt,
    agents,
    // ... 更多参数
  })

// 最终 system prompt = defaultSystemPrompt + custom + append
// userContext 和 systemContext 通过 prependUserContext/appendSystemContext 注入
```

---

## 7. Compaction System — 压缩系统

### 7.1 四层压缩架构

```
Compaction 系统 (services/compact/):

1. MicroCompact     — 单个 tool result 内容清除 (轻量, 节省 token)
2. AutoCompact      — 自动检测 token 警告 → 触发压缩
3. ReactiveCompact  — API 返回 max_output_tokens 时被动触发
4. ContextCollapse  — 深度压缩 (摘要 + 归档大上下文)
5. SnipCompact      — 历史截断 (HISTORY_SNIP feature, 仅 SDK)
```

### 7.2 MicroCompact

```typescript
// services/compact/microCompact.ts
// 清除已读文件/工具结果的历史内容, 替换为占位符:

const COMPACTABLE_TOOLS = [
  FILE_READ_TOOL_NAME,    // read_file → "[Old tool result content cleared]"
  ...SHELL_TOOL_NAMES,    // bash, powershell
  GREP_TOOL_NAME,          // grep
  GLOB_TOOL_NAME,          // glob
  WEB_SEARCH_TOOL_NAME,    // web_search
  WEB_FETCH_TOOL_NAME,     // web_fetch
  FILE_EDIT_TOOL_NAME,     // edit
  FILE_WRITE_TOOL_NAME,    // write
]
```

### 7.3 AutoCompact

```typescript
// services/compact/autoCompact.ts
// calculateTokenWarningState() → 检查 token 使用率
// isAutoCompactEnabled() → 检查是否启用
// 触发条件: prompt_tokens + output_tokens 接近 context_length 的阈值
```

### 7.4 Compaction 主流程 (macro)

```typescript
// services/compact/compact.ts
// buildPostCompactMessages() — 核心压缩流程:
//
// 1. groupMessagesByApiRound() — 按 API 调用轮次分组
// 2. analyzeContext() — 分析上下文使用
// 3. 使用辅助 Agent (fork) 做摘要:
//    - runForkedAgent() → 生成压缩后的消息
//    - getCompactPrompt() → 压缩提示词模板
//    - getPartialCompactPrompt() → 部分压缩 (保留最近 N 轮)
// 4. createCompactBoundaryMessage() — 创建压缩边界标记
// 5. 重新注入附件 (skill listings, memory, tools)
// 6. notifyCompaction() — 通知 prompt cache 失效
// 7. executePreCompactHooks / executePostCompactHooks
```

### 7.5 ContextCollapse

```typescript
// services/contextCollapse/
// 深度压缩 + 持久化:
// - 类似于 Git squash: 将多个 compact 的上下文合并
// - 保留最近上下文, 旧的做摘要 → 存档到文件
// - 支持 resume 时还原
```

---

## 8. Tool System — 工具系统

### 8.1 Tool 类型定义

```typescript
// src/Tool.ts — 工具的核心类型

type Tool = {
  name: string
  description: string
  inputSchema: z.ZodSchema           // Zod 验证 schema
  prompt?: string                     // 工具使用指引 (注入 system prompt)
  isConcurrencySafe?: (input) => boolean  // 并发安全判断
  isReadOnly?: (input) => boolean     // 只读判断 (权限模式)
  needsPermissions?: boolean          // 是否需要权限
  promptIfDisabled?: string           // 禁用时的提示
  // ... 更多工具元数据
}

type Tools = Tool[]
```

### 8.2 工具目录结构

每个工具是一个独立目录:

```
tools/BashTool/
    BashTool.ts          ← 工具实现 (符合 Tool 接口)
    prompt.ts            ← 工具使用指引 (注入 system prompt)
    toolName.ts          ← 工具名称常量
tools/FileReadTool/
    FileReadTool.ts
    prompt.ts
tools/FileWriteTool/
    ...
tools/AgentTool/        ← Agent/Subagent 工具 (最复杂的工具)
    AgentTool.tsx
    loadAgentsDir.ts
    agentColorManager.ts
    ...
```

### 8.3 并发安全分区

```typescript
// services/tools/toolOrchestration.ts — 工具分批算法:

function partitionToolCalls(
  toolUseMessages: ToolUseBlock[],
  toolUseContext: ToolUseContext,
): Batch[] {
  // 将工具调用分为连续批次:
  // - 连续的 concurrency-safe 工具 → 同一批次 (并行)
  // - 每个 non-concurrency-safe 工具 → 独立批次 (串行)
  return toolUseMessages.reduce((acc, toolUse) => {
    const tool = findToolByName(tools, toolUse.name)
    const isConcurrencySafe = tool?.isConcurrencySafe(parsedInput) ?? false
    if (isConcurrencySafe && acc[acc.length - 1]?.isConcurrencySafe) {
      acc[acc.length - 1].blocks.push(toolUse)
    } else {
      acc.push({ isConcurrencySafe, blocks: [toolUse] })
    }
    return acc
  }, [])
}

const MAX_TOOL_USE_CONCURRENCY = 10  // 环境变量可覆盖
```

### 8.4 StreamingToolExecutor

```typescript
// services/tools/StreamingToolExecutor.ts
// 流式场景: tool_use 块边到边执行

class StreamingToolExecutor {
  private tools: TrackedTool[]  // 工具队列
  private siblingAbortController  // 兄弟工具中断控制

  addTool(block, assistantMessage)
    // 添加工具到队列 → 自动开始执行 (如果条件允许)

  discard()
    // 丢弃所有待处理和进行中的工具 (流式 fallback 时用)

  async *getRemainingResults()
    // 按原始顺序 yield 结果 (即使并行执行)

  // TrackedTool 状态机: queued → executing → completed | yielded
}
```

### 8.5 工具列表 (50+)

| 分类 | 工具名 |
|------|--------|
| 文件 | FileReadTool, FileWriteTool, FileEditTool, GlobTool, GrepTool |
| Shell | BashTool, PowerShellTool |
| Web | WebSearchTool, WebFetchTool |
| Agent | AgentTool (子代理), TaskCreateTool, TaskListTool, TaskGetTool, TaskOutputTool, TaskUpdateTool, TaskStopTool |
| Plan | EnterPlanModeTool, ExitPlanModeTool |
| Worktree | EnterWorktreeTool, ExitWorktreeTool |
| 记忆 | (通过 memdir + write 工具) |
| 交互 | AskUserQuestionTool |
| LSP | LSPTool |
| Todo | TodoWriteTool |
| MCP | MCPTool, ListMcpResourcesTool, ReadMcpResourceTool, McpAuthTool |
| 技能 | SkillTool |
| 团队 | TeamCreateTool, TeamDeleteTool |
| 工具发现 | ToolSearchTool |
| 其他 | NotebookEditTool, SleepTool, SendMessageTool, BriefTool, ConfigTool, Cron工具, RemoteTriggerTool, SyntheticOutputTool |

---

## 9. Task System — 后台任务系统

### 9.1 Task 类型

```typescript
// src/Task.ts
type TaskType =
  | 'local_bash'           // 后台 bash 命令
  | 'local_agent'          // 子 Agent (subagent)
  | 'remote_agent'         // 远程 Agent
  | 'in_process_teammate'  // 进程内 teammate
  | 'local_workflow'       // Workflow (工作流编排)
  | 'monitor_mcp'          // MCP 监控
  | 'dream'                // Dream (空闲推测)

type TaskStatus =
  | 'pending'              // 等待执行
  | 'running'              // 运行中
  | 'completed'            // 已完成
  | 'failed'               // 失败
  | 'killed'               // 被中止

function isTerminalTaskStatus(status: TaskStatus): boolean {
  return status === 'completed' || status === 'failed' || status === 'killed'
}
```

### 9.2 Task ID 格式

```typescript
// 前缀 + 8 位 base36 随机字符

const TASK_ID_PREFIXES = {
  local_bash: 'b',        // "bf3am87x"
  local_agent: 'a',       // "a-gpt-builder-x1k2j3n4" (agent label + hex)
  remote_agent: 'r',
  in_process_teammate: 't',
  local_workflow: 'w',
  monitor_mcp: 'm',
  dream: 'd',
}

// AgentId 特殊格式: a + optional <label>- + 16 hex chars
// 正则: /^a(?:.+-)?[0-9a-f]{16}$/
```

### 9.3 Task 状态基类

```typescript
type TaskStateBase = {
  id: string
  type: TaskType
  status: TaskStatus
  description: string
  toolUseId?: string        // 关联的 tool_use ID
  startTime: number
  endTime?: number
  totalPausedMs?: number
  outputFile: string        // 输出文件路径
  outputOffset: number      // 输出偏移 (增量读取)
  notified: boolean         // 已完成通知标记
}
```

### 9.4 Workflow 系统

```typescript
// 复杂的多 Agent 编排工作流
// - pipeline: 逐阶段串行, 每阶段内并行
// - parallel: 全并行
// - agent(): 启动单个子 Agent
// - 脚本中使用 export const meta = { name, description, phases } 声明
```

---

## 10. AppState — 应用状态管理

### 10.1 架构

```typescript
// src/state/AppStateStore.ts — 全局 UI 状态定义
// 使用 React Context + useSyncExternalStore (类似 Redux/zustand)

type AppState = DeepImmutable<{
  settings: SettingsJson                    // 用户设置
  verbose: boolean
  mainLoopModel: ModelSetting               // 主循环模型
  mainLoopModelForSession: ModelSetting     // 会话级模型覆盖
  statusLineText: string | undefined
  expandedView: 'none' | 'tasks' | 'teammates'
  isBriefOnly: boolean
  toolPermissionContext: ToolPermissionContext // 权限上下文
  spinnerTip?: string
  agent: string | undefined                 // --agent CLI flag
  kairosEnabled: boolean                    // Assistant 模式

  // 桥接/远程
  replBridgeEnabled: boolean
  replBridgeConnected: boolean
  replBridgeSessionActive: boolean
  replBridgeConnectUrl: string | undefined
  replBridgeSessionUrl: string | undefined

  // 协调器
  coordinatorTaskIndex: number
  viewSelectionMode: 'none' | 'selecting-agent' | 'viewing-agent'

  // 任务
  tasks: TaskState[]

  // 通知
  queuedNotifications: Notification[]

  // 提示建议
  promptSuggestion: {
    text: string
    promptId: 'user_intent' | 'stated_intent' | null
    generationRequestId: string | null
  }

  // 推测
  speculation: SpeculationState             // 空闲推测状态

  // IDE 桥接
  questionPreviewFormat: 'markdown' | 'html' | undefined

  // ... 更多 UI 状态字段 (~50+ 字段)
}>
```

### 10.2 状态管理 API

```typescript
// React/Ink 组件中使用:
const store = useAppStore()                 // 获取 store
const verbose = useAppState(s => s.verbose) // 订阅单个字段
store.setState(prev => ({ ...prev, ... }))  // 更新状态

// QueryEngine 中使用:
const appState = config.getAppState()       // 获取当前快照
config.setAppState(f)                       // 函数式更新
```

### 10.3 与非 UI 状态的分离

Claude Code 明确分离了两层状态:

| 状态层 | 位置 | 用途 |
|--------|------|------|
| UI State | `state/AppState.tsx` | React 组件状态, 通过 Context+订阅模式 |
| Bootstrap State | `bootstrap/state.ts` | 非 UI 全局状态 (cwd, sessionId, model, cost) |

Bootstrap State 是纯 OOP 单例, AppState 是 React 响应式状态 — 两者互不依赖。

---

## 11. Permission System — 权限系统

### 11.1 权限模式

```typescript
// src/types/permissions.ts
type PermissionMode =
  | 'default'           // 默认: 询问用户
  | 'acceptEdits'       // 自动接受编辑
  | 'bypassPermissions' // 绕过权限 (需要 trust)
  | 'plan'              // 计划模式

type PermissionResult = {
  behavior: 'allow' | 'deny' | 'ask'
  updatedInput?: Record<string, unknown>
  message?: string
}
```

### 11.2 ToolPermissionContext

```typescript
type ToolPermissionContext = DeepImmutable<{
  mode: PermissionMode
  additionalWorkingDirectories: Map<string, AdditionalWorkingDirectory>
  alwaysAllowRules: ToolPermissionRulesBySource   // 允许规则
  alwaysDenyRules: ToolPermissionRulesBySource    // 拒绝规则
  alwaysAskRules: ToolPermissionRulesBySource     // 询问规则
  isBypassPermissionsModeAvailable: boolean
  isAutoModeAvailable?: boolean
  strippedDangerousRules?: ToolPermissionRulesBySource
  shouldAvoidPermissionPrompts?: boolean          // 后台 Agent
  awaitAutomatedChecksBeforeDialog?: boolean      // 协调器 worker
  prePlanMode?: PermissionMode                    // 计划模式前的模式
}>
```

### 11.3 权限决策链

```
canUseTool(tool, input, context, assistantMessage, toolUseID):
│
├── 1. Check alwaysDenyRules → 匹配 → deny
├── 2. Check alwaysAllowRules → 匹配 → allow
├── 3. Check alwaysAskRules → 匹配 → ask
├── 4. Tool 的 needsPermissions → false → allow
├── 5. 权限模式检查:
│   ├── bypassPermissions → allow (安全警告 + 中断)
│   └── default → ask (显示权限对话框)
└── 6. Decision Pipeline 自定义规则
```

---

## 12. Bootstrap 全局状态

### 12.1 状态结构

```typescript
// src/bootstrap/state.ts — 纯 OOP, 非 React
type State = {
  // 目录
  originalCwd: string          // 原始工作目录 (符号链接解析后)
  projectRoot: string          // 项目根 (--worktree flag 或 cwd)
  cwd: string                  // 当前工作目录

  // 会话
  sessionId: SessionId         // 当前会话 ID (branded)
  parentSessionId: SessionId   // 父会话 ID (lineage)

  // 模型
  mainLoopModelOverride: ModelSetting | undefined
  initialMainLoopModel: ModelSetting
  modelStrings: ModelStrings | null
  modelUsage: { [modelName: string]: ModelUsage }

  // 计费
  totalCostUSD: number
  totalAPIDuration: number
  totalAPIDurationWithoutRetries: number
  totalToolDuration: number

  // 统计
  totalLinesAdded: number
  totalLinesRemoved: number
  hasUnknownModelCost: boolean

  // 交互
  isInteractive: boolean
  kairosActive: boolean
  strictToolResultPairing: boolean  // HFI: strict 模式

  // Flag 设置
  flagSettingsPath: string | undefined
  flagSettingsInline: Record<string, unknown> | null
  allowedSettingSources: SettingSource[]

  // 认证
  sessionIngressToken: string | null | undefined
  oauthTokenFromFd: string | null | undefined
  apiKeyFromFd: string | null | undefined

  // 遥测
  meter: Meter | null
  sessionCounter, locCounter, prCounter, commitCounter,
  costCounter, tokenCounter, codeEditToolDecisionCounter: AttributedCounter | null
  statsStore: { observe(name: string, value: number): void } | null
  loggerProvider, eventLogger, meterProvider, tracerProvider

  // Agent
  agentColorMap: Map<string, AgentColorName>
  agentColorIndex: number

  // API
  lastAPIRequest: Omit<BetaMessageStreamParams, 'messages'> | null
  lastAPIRequestMessages: BetaMessageStreamParams['messages'] | null
  lastClassifierRequests: unknown[] | null

  // Memory
  cachedClaudeMdContent: string | null

  // Error
  inMemoryErrorLog: Array<{ error: string; timestamp: string }>

  // Plugins
  inlinePlugins: Array<string>    // --plugin-dir
  useCoworkPlugins: boolean
  chromeFlagOverride: boolean | undefined

  // Permissions
  sessionBypassPermissionsMode: boolean

  // Cron
  scheduledTasksEnabled: boolean
  sessionCronTasks: SessionCronTask[]

  // Teams
  sessionCreatedTeams: Set<string>

  // Session
  sessionSource: string | undefined
  clientType: string
  userMsgOptIn: boolean
  questionPreviewFormat: 'markdown' | 'html' | undefined

  // Effort
  turnHookDurationMs, turnToolDurationMs,
  turnClassifierDurationMs, turnHookCount,
  turnToolCount, turnClassifierCount: number
}
```

### 12.2 API

```typescript
// 核心 API:
getSessionId(): SessionId
getProjectRoot(): string
getOriginalCwd(): string
isSessionPersistenceDisabled(): boolean

// 会话切换:
switchSession(newSessionId: SessionId): void

// 模型:
getMainLoopModel(): ModelSetting
parseUserSpecifiedModel(spec: string): ModelSetting

// 计费:
getTotalCost(): number
getTotalAPIDuration(): number

// 文件历史:
getFileHistory(): FileHistoryState

// Cron:
isCronSchedulerActive(): boolean
getSessionCronTasks(): SessionCronTask[]

// 压缩标记:
markPostCompaction(): void
```

---

## 13. Message 类型体系

(注: `src/types/message.ts` 在源码快照中不存在 — 可能是构建时从 protobuf 或 schema 生成的。以下从实际使用中推断)

### 13.1 推断的消息类型

```typescript
// 从各模块 import 推断:
type UserMessage = {
  type: 'user'
  uuid: UUID
  parentUuid: UUID
  content: string | ContentBlockParam[]  // 可能是文本或多模态
  toolUseResult?: string                  // tool_result 的文本摘要
  sourceToolAssistantUUID?: UUID          // 工具来源 assistant 消息 UUID
}

type AssistantMessage = {
  type: 'assistant'
  uuid: UUID
  parentUuid: UUID
  message: {
    content: ContentBlockParam[]  // text | tool_use | thinking
    model: string
    stop_reason: string
    usage: Usage
  }
  apiError?: string
}

type SystemMessage = {
  type: 'system'
  uuid: UUID
  parentUuid: UUID
  content: string
}

type AttachmentMessage = {
  type: 'attachment'
  uuid: UUID
  parentUuid: UUID
  content: string
  // attachment 元数据
}

type ProgressMessage = {
  type: 'progress'
  // 临时 UI 状态 — 不参与 parentUuid 链, 不持久化
}

type TombstoneMessage = {
  type: 'tombstone'
  // 标记被截断/压缩的消息
}

type ToolUseSummaryMessage = {
  type: 'tool_use_summary'
  // 大量 tool_use 的批处理摘要
}

type SystemCompactBoundaryMessage = {
  type: 'system'
  compactBoundary: true
  // 压缩边界标记 — getMessagesAfterCompactBoundary() 使用
}
```

### 13.2 UUID 链

```
parentUuid 链: 每个消息有一个 parentUuid 指向前一条消息
  user_msg_1 → null (root)
  assistant_msg_1 → user_msg_1 (parentUuid)
  tool_result_1 → assistant_msg_1
  assistant_msg_2 → tool_result_1
  ...

Progress messages 不参与此链 (它们不是 transcript entries)
```

---

## 14. Plugin System — 插件系统

### 14.1 架构

Claude Code 的插件系统基于文件系统 + Marketplace:

```
~/.claude/plugins/
    <plugin-name>/
        plugin.json   ← 清单文件
        index.js      ← 插件入口

--plugin-dir <path>   ← 命令行注入临时插件目录

--cowork               ← 使用 cowork_plugins 目录
```

### 14.2 Plugin 加载

```typescript
// src/utils/plugins/pluginLoader.ts

// 插件来源:
// 1. ~/.claude/plugins/  (用户安装)
// 2. --plugin-dir CLI flag (会话级)
// 3. .claude/plugins/ (项目级)
// 4. cowork_plugins/ (cowork 模式)

// 缓存:
loadAllPluginsCacheOnly(): Promise<LoadedPlugin[]>
```

### 14.3 Hook System

```typescript
// src/types/hooks.ts
type HookEvent = 'preToolUse' | 'postToolUse' | 'notification' | ...
type HookCallbackMatcher = {
  event: HookEvent
  toolName?: string
  handler: (event: HookEvent, data: any) => void
}
```

---

## 15. Agent/Subagent — 多代理系统

### 15.1 Agent 类型

```typescript
// src/tools/AgentTool/loadAgentsDir.ts
type AgentDefinition = {
  name: string
  description: string
  prompt: string
  tools?: string[]           // 可用工具列表
  model?: string             // 覆盖模型
  // ... 更多配置
}

// Agent 来源:
// 1. .claude/agents/*.md — 用户定义的 Agent (--agents flag)
// 2. ~/.claude/agents/ — 全局 Agent
// 3. 内置 Agent 定义
```

### 15.2 子代理执行

```
AgentTool → spawn LocalAgentTask:
  1. 构建子代理 system prompt (基于 AgentDefinition.prompt)
  2. 限制工具集 (只允许 tools 字段指定的工具)
  3. 分配独立的 QueryEngine 或共享 REPL
  4. 子代理产生 messages → 返回给父代理
```

### 15.3 Workflow 编排

```
Workflow (local_workflow task):
  - 脚本声明 meta: { name, description, phases }
  - agent() → 启动 subagent
  - parallel() → 并行执行
  - pipeline() → 流水线
  - phase() → 阶段分组
  - budget: { total, spent(), remaining() }
```

---

## 16. 关键数据结构总览

### 16.1 一次完整会话的数据结构

```
会话 = {
  // ── Bootstrap State ──
  sessionId: "abc123..."              (SessionId branded)
  parentSessionId: undefined | "xyz"  (lineage)
  originalCwd: "/home/user/project"
  projectRoot: "/home/user/project"
  cwd: "/home/user/project"

  // ── JSONL Transcript ──
  transcript: [
    { type: 'user', uuid: ..., parentUuid: null, content: "Build a...", ... },
    { type: 'assistant', uuid: ..., parentUuid: ..., message: { content: [...], ... } },
    { type: 'user', uuid: ..., parentUuid: ..., content: [{type: 'tool_result', ...}], ... },
    ...
  ]

  // ── Memory ──
  memory: {
    entrypoint: MEMORY.md (200行/25KB 限制)
    autoMemory: [auto-memory/*.md]
    teamMemory: (optional)
  }

  // ── AppState (UI) ──
  appState: {
    verbose: false,
    mainLoopModel: "claude-sonnet-4-6",
    toolPermissionContext: { mode: 'default', ... },
    tasks: [...],
    messages: [...],
    ...
  }

  // ── File History ──
  fileHistory: {
    snapshots: [...],     // write_file 前的快照
    undo: [...]           // 可撤销状态
  }

  // ── Content Replacements ──
  contentReplacements: [...],  // 压缩时的内容替换决策

  // ── Task States ──
  tasks: [
    {
      id: 'b...',
      type: 'local_bash',
      status: 'running',
      description: 'npm install',
      outputFile: '/tmp/claude/tasks/b...jsonl',
      ...
    },
    {
      id: 'a-builder-a1b2c3d4...',
      type: 'local_agent',
      status: 'completed',
      description: 'Build React component',
      ...
    }
  ],

  // ── Cost/Usage ──
  totalCostUSD: 0.42,
  totalAPIDuration: 4500,
  modelUsage: {
    'claude-sonnet-4-6': { inputTokens: 50000, outputTokens: 12000, ... }
  }
}
```

---

## 17. 与 hermes-agent 的架构对比

| 维度 | Claude Code | hermes-agent |
|------|-------------|-------------|
| **语言/运行时** | TypeScript + Bun | Python 3.12+ |
| **UI** | React/Ink 终端 UI (~800KB main.tsx) | Text-based (Rich/Textual) |
| **Agent 循环** | `query()` generator + `QueryEngine` 类 | `run_conversation()` 函数 |
| **会话存储** | JSONL transcript per session | SQLite (SessionDB, FTS5) |
| **记忆** | MEMORY.md 文件系统 (Markdown) | MemoryProvider ABC (多后端) |
| **压缩** | 4层 (Micro/Auto/Reactive/ContextCollapse) | ContextEngine ABC (默认 compressor) |
| **工具系统** | 每工具独立目录 + Zod schema | 中央注册表 + AST 发现 |
| **权限** | ToolPermissionContext (allow/deny/ask rules) | ToolGuardrailController (block/allow/halt) |
| **ID 体系** | Branded types (SessionId, AgentId) | Plain strings |
| **状态管理** | AppState (React) + Bootstrap (纯 OOP) | AIAgent 实例字段 |
| **插件系统** | 文件系统 plugins + hooks | 4源 (bundled/user/project/pip) + hooks |
| **子代理** | AgentTool + LocalAgentTask + Workflow | delegate_task 工具 |
| **LSP** | LSPTool + agent/lsp/ | agent/lsp/ (Python 多进程) |
| **消息模型** | UUID 链 (parentUuid) | SQLite 时间戳 + AUTOINCREMENT |
| **ensureToolResultPairing** | ✅ 发送前验证 | ❌ 无 |
| **StreamingToolExecutor** | ✅ 流式即执行 | ❌ 先等完整响应 |
| **Token Budget** | ✅ +500k 自动续行 | ❌ 无此特性 |
| **Worktree** | ✅ EnterWorktreeTool + ExitWorktreeTool | ❌ 无 |
| **Context Collapse** | ✅ 深度压缩 + 存档 | ❌ 仅 LLM 摘要 |
| **MicroCompact** | ✅ 单 tool result 清除 | ❌ 无 |
| **推测执行** | ✅ SpeculationState (空闲推测) | ❌ 无 |
| **Cron** | ✅ CronCreate + scheduled_tasks.json | ✅ cron 模块 |

### 关键差异

1. **Claude Code 使用 JSONL + parentUuid 链**, hermes-agent 使用 SQLite + AUTOINCREMENT。JSONL 更简单但搜索不如 SQLite FTS5。

2. **Claude Code 的内存模型更简单**: 文件系统直接读写 (MEMORY.md), hermes-agent 有更复杂的 MemoryProvider 插件体系。

3. **Claude Code 的压缩系统更精细**: 4 层压缩 (micro/auto/reactive/collapse), hermes-agent 是统一的 ContextEngine。

4. **Claude Code 有 Worktree**: 支持 `EnterWorktreeTool`/`ExitWorktreeTool` 创建隔离的 git worktree, hermes-agent 没有此概念。

5. **Claude Code 流式优先**: `StreamingToolExecutor` 在 tool_use 边到边时即开始执行, hermes-agent 先等完整响应后解析。

6. **hermes-agent 有 Centric DB**: 单一 SQLite DB 存储所有会话, 支持 FTS5 跨会话搜索。Claude Code 的 JSONL 文件更适合 per-session 操作。

7. **Claude Code 的权限系统更模块化**: `alwaysAllowRules / alwaysDenyRules / alwaysAskRules` 三分法 + 每工具 `needsPermissions`。

---

## 18. 对 mochagent 的架构启示

### 18.1 可直接借鉴的模式 (优先级排序)

| # | 模式 | 来源 | 建议 |
|---|------|------|------|
| 1 | **QueryEngine 类** | Claude Code | mochagent 的 ReActAgent → 提取为 AgentEngine, 单例 per 对话 |
| 2 | **ensureToolResultPairing** | Claude Code | 在发送 API 调用前验证 tool_use/tool_result 配对 |
| 3 | **StreamingToolExecutor** | Claude Code | 工具流式到达即执行, 非等完整响应 |
| 4 | **MicroCompact** | Claude Code | 清除旧 tool result 内容为占位符, 节省 token |
| 5 | **并发安全分批 (partitionToolCalls)** | Claude Code | 连续并发安全工具 → 一批并行, 非安全工具 → 串行 |
| 6 | **Branded Types** | Claude Code | Java 可用 `@IdType` 注解或专用 wrapper 类型 |
| 7 | **4层压缩** | Claude Code | 轻量 micro + 自动 auto + 被动 reactive + 深度 collapse |
| 8 | **Bootstrap State** | Claude Code | 将 cwd/sessionId/modelUsage 等非 UI 状态从 AppState 分离 |
| 9 | **parentSessionId 链** | 两者都有 | mochagent 添加 session lineage 支持 |
| 10 | **React Context + useSyncExternalStore** | Claude Code | Java 可用 EventBus 或 Observable Store 实现类似模式 |
| 11 | **Worktree** | Claude Code | Git worktree 隔离 — 对 mochagent 的 Git 操作工具有用 |
| 12 | **Task 状态机** | Claude Code | pending→running→completed/failed/killed + isTerminal 守卫 |
| 13 | **Tool 目录结构** | Claude Code | 每工具独立目录 (prompt+实现), 可借鉴目录组织 |
| 14 | **Zod 验证** | Claude Code | mochagent 已有 schema 验证 (如 Pydantic 风格), 对齐 |
| 15 | **Permission 三分法** | Claude Code | allow/deny/ask rules + needsPermissions per tool |

### 18.2 不推荐借鉴的

| 模式 | 原因 |
|------|------|
| JSONL session storage | SQLite/H2 更适合 Java 生态 + FTS 搜索 |
| React/Ink UI | Java 用不同 UI 方案 |
| Bun feature() DCE | Java 用不同编译时优化 |
| 文件系统直接读写记忆 | MemoryProvider 插件体系更灵活 |
| Protobuf 生成的消息类型 | Java 可直接定义 typed records |

### 18.3 优先级路线图建议

**Phase 1** (立即):
- AgentEngine 提取 (类 QueryEngine)
- ensureToolResultPairing
- 并发安全工具分区
- parentSessionId 链

**Phase 2** (短期):
- MicroCompact (tool result 清除)
- StreamingToolExecutor 预留
- Task 状态机标准化
- Branded ID types

**Phase 3** (中期):
- 4层压缩系统
- Worktree 支持
- Token budget 自动续行
- Bootstrap State 分离

**Phase 4** (远期):
- Permission 三分法重构
- 深层 ContextCollapse
- Cron/scheduled tasks
- 推测执行

---

> **注**: 本文档基于 Claude Code TypeScript 源码快照 (2026-03-31 公开) 分析编写。
> 相关文档: [hermes-agent-deep-analysis.md](./hermes-agent-deep-analysis.md)
