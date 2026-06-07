# Claude Code 会话与对话存储结构 — 深入分析

> 分析日期: 2026-06-07
> 分析对象: Claude Code TypeScript 源码中的会话存储系统

---

## 目录

1. [存储架构总览](#1-存储架构总览)
2. [文件系统布局](#2-文件系统布局)
3. [JSONL Transcript 格式](#3-jsonl-transcript-格式)
4. [UUID 链 (parentUuid) — 核心机制](#4-uuid-链-parentuuid--核心机制)
5. [Message 类型体系](#5-message-类型体系)
6. [Project 类 — 持久化引擎](#6-project-类--持久化引擎)
7. [会话元数据层 (Metadata Layer)](#7-会话元数据层-metadata-layer)
8. [会话搜索与列表 (LogOption)](#8-会话搜索与列表-logoption)
9. [子代理会话存储](#9-子代理会话存储)
10. [会话切换与恢复 (Resume)](#10-会话切换与恢复-resume)
11. [Compaction 对会话存储的影响](#11-compaction-对会话存储的影响)
12. [FileHistory — 文件历史与撤销](#12-filehistory--文件历史与撤销)
13. [与 mochagent 的对比与建议](#13-与-mochagent-的对比与建议)

---

## 1. 存储架构总览

```
┌────────────────────────────────────────────────────────────────┐
│                   SESSION STORAGE LAYERS                        │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Layer 1: JSONL Transcript (核心)                               │
│    ~/.claude/projects/<projectSlug>/<sessionId>.jsonl          │
│    └── UUID 链 — 每条消息有 uuid + parentUuid                   │
│                                                                 │
│  Layer 2: Session Metadata (轻量)                               │
│    ~/.claude/projects/<projectSlug>/<sessionId>.meta.json      │
│    └── customTitle, tag, agentName, agentColor, summary         │
│                                                                 │
│  Layer 3: Subagent Transcripts (子代理)                         │
│    ~/.claude/projects/<projectSlug>/<sessionId>/subagents/     │
│    └── agent-<agentId>.jsonl + agent-<agentId>.meta.json       │
│                                                                 │
│  Layer 4: Remote Agent Metadata (远程代理)                      │
│    ~/.claude/projects/<projectSlug>/<sessionId>/remote-agents/ │
│    └── remote-agent-<taskId>.meta.json                         │
│                                                                 │
│  Layer 5: File History (文件快照, undo)                         │
│    ~/.claude/projects/<projectSlug>/<sessionId>/file-history/  │
│    └── per-file snapshots before write/edit                    │
│                                                                 │
│  Layer 6: Worktree Sessions (Git 隔离工作树)                    │
│    .claude/worktrees/<name>/ → git worktree                    │
│    └── PersistedWorktreeSession 记录在 transcript metadata 中   │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

---

## 2. 文件系统布局

### 2.1 目录结构

```
~/.claude/                              ← Claude Code 配置根目录
├── projects/                           ← 项目会话存储
│   └── <projectSlug>/                  ← 项目标识 (cwd 的 djb2Hash)
│       ├── <sessionId>.jsonl           ← 主转录文件 (JSONL)
│       ├── <sessionId>.meta.json       ← 轻量元数据
│       ├── <sessionId>/                ← 会话子目录
│       │   ├── subagents/              ← 子代理转录
│       │   │   ├── agent-<agentId>.jsonl
│       │   │   ├── agent-<agentId>.meta.json
│       │   │   └── workflows/<runId>/agent-<agentId>.jsonl
│       │   └── remote-agents/          ← 远程代理元数据
│       │       └── remote-agent-<taskId>.meta.json
│       └── <anotherSessionId>.jsonl    ← 另一个会话
├── plugins/
├── settings.json
├── credentials.json
└── ...
```

### 2.2 Project Slug 生成

```typescript
// src/utils/sessionStoragePortable.ts
// 使用 djb2Hash 将 cwd 路径转换为文件名安全的 slug
// 确保跨平台兼容 (Windows/Mac/Linux 路径差异)

export function sanitizePath(projectDir: string): string {
  // 将绝对路径转为安全的目录名
}

export const getProjectDir = memoize((projectDir: string): string => {
  return join(getProjectsDir(), sanitizePath(projectDir))
})
```

### 2.3 Transcript 路径解析

```typescript
// 当前会话的 transcript:
export function getTranscriptPath(): string {
  const projectDir = getSessionProjectDir() ?? getProjectDir(getOriginalCwd())
  return join(projectDir, `${getSessionId()}.jsonl`)
}

// 指定会话的 transcript:
export function getTranscriptPathForSession(sessionId: string): string {
  // 对当前会话: 使用 getSessionProjectDir() (原子切换保证)
  if (sessionId === getSessionId()) return getTranscriptPath()
  // 对其他会话: 只能通过 originalCwd 推断
  const projectDir = getProjectDir(getOriginalCwd())
  return join(projectDir, `${sessionId}.jsonl`)
}
```

**关键设计: ** 当前会话的 project dir 与 session ID 是原子切换的 (通过 `switchActiveSession` → `switchSession`)，防止 sessionId 和 projectDir 不同步导致 hooks 找不到 transcript (gh-30217)。

---

## 3. JSONL Transcript 格式

### 3.1 核心格式

每行一条 JSON 记录:

```jsonl
{"type":"user","uuid":"a1b2c3d4-...","parentUuid":null,"sessionId":"abc123","cwd":"/home/user/project","timestamp":"2026-06-07T10:30:00.000Z","version":"2.1.168","userType":"external","entrypoint":"cli","content":"Build a React component library...","isMeta":false}
{"type":"assistant","uuid":"b2c3d4e5-...","parentUuid":"a1b2c3d4-...","sessionId":"abc123","cwd":"/home/user/project","timestamp":"2026-06-07T10:30:05.000Z","message":{"model":"claude-sonnet-4-6","content":[{"type":"text","text":"I'll start by..."},{"type":"tool_use","id":"toolu_01...","name":"bash","input":{"command":"npm init -y"}}],"usage":{"input_tokens":5000,"output_tokens":500}}}
{"type":"user","uuid":"c3d4e5f6-...","parentUuid":"b2c3d4e5-...","sessionId":"abc123","content":[{"type":"tool_result","tool_use_id":"toolu_01...","content":"Wrote to package.json..."}],"toolUseResult":"Wrote to package.json...","sourceToolAssistantUUID":"b2c3d4e5-..."}
...
```

### 3.2 消息角色分类

```
TranscriptMessage (参与 UUID 链，持久化):
─ user        — 用户消息或 tool_result
─ assistant   — 模型响应 (text + tool_use + thinking)
─ attachment  — 记忆/上下文附件
─ system      — 系统消息 (压缩边界、通知等)

MetadataMessage (不参与 UUID 链):
─ summary     — AI 摘要
─ custom-title — 用户标题
─ ai-title    — AI 标题
─ tag         — 标签
─ agent-name  — Agent 名称
─ agent-color — Agent 颜色

进度消息 (不持久化):
─ progress    — 临时 UI 进度条
─ bash_progress / powershell_progress / mcp_progress / sleep_progress
```

### 3.3 EpisodeProgress 类型 (非 Transcript)

```typescript
// 高频工具进度 (1/sec for Sleep, per-chunk for Bash)
// UI-only: 不发给 API, 工具完成后不渲染
// loadTranscriptFile 中跳过这些旧条目
const EPHEMERAL_PROGRESS_TYPES = new Set([
  'bash_progress',
  'powershell_progress',
  'mcp_progress',
  ...sleep_progress,  // 仅在 PROACTIVE 或 KAIROS feature 下
])
```

---

## 4. UUID 链 (parentUuid) — 核心机制

### 4.1 基本概念

每条 transcript 消息携带:
- `uuid: UUID` — 自身唯一标识
- `parentUuid: UUID | null` — 指向前一条消息的 UUID

形成单向链表:
```
user_msg_1 (parentUuid=null, uuid=aaa)
  ↓
assistant_msg_1 (parentUuid=aaa, uuid=bbb)
  ↓
tool_result_1 (parentUuid=bbb, uuid=ccc)
  ↓
assistant_msg_2 (parentUuid=ccc, uuid=ddd)
  ↓
...
```

### 4.2 插入算法 (insertMessageChain)

```typescript
// src/utils/sessionStorage.ts
async insertMessageChain(
  messages: Message[],
  startingParentUuid?: UUID | null,
  options?: { sessionFileOverride?: string }
): Promise<void> {
  let parentUuid: UUID | null = startingParentUuid ?? null

  for (const message of messages) {
    // Progress messages 不参与 UUID 链
    if (message.type === 'progress') continue

    // 检查 dedup: 如果消息已经持久化过, 跳过但更新 parentUuid 游标
    if (alreadyRecorded(message.uuid)) {
      parentUuid = message.uuid  // ← 关键: 游标跳过已记录消息
      continue
    }

    // 压缩边界 (compactBoundary) 的特殊处理:
    // - 自身 parentUuid = null (新根)
    // - 但记录 logicalParentUuid 用于链重建
    const isCompactBoundary = message.type === 'system' && message.compactBoundary
    let effectiveParentUuid = parentUuid // 默认继承游标

    // 序列化为 SerializedMessage
    const serialized: SerializedMessage = {
      ...message,
      parentUuid: isCompactBoundary ? null : effectiveParentUuid,
      logicalParentUuid: isCompactBoundary ? parentUuid : undefined,
      sessionId: getSessionId(),
      cwd: getCwd(),
      timestamp: new Date().toISOString(),
      version: VERSION,
      userType: getUserType(),
      entrypoint: getEntrypoint(),
    }

    // 加入写入队列 (异步批量)
    await enqueueWrite(transcriptPath, serialized)

    // 更新游标
    parentUuid = message.uuid
  }
}
```

### 4.3 链重建 (buildConversationChain)

```typescript
// 从 JSONL 文件重建对话链:
// 1. 加载所有条目到 Map<uuid, Entry>
// 2. 从最后一条消息 (leaf) 开始 walk
// 3. 沿 parentUuid 反向遍历到根
// 4. 反转顺序 → root-first order

function buildConversationChain(entries: TranscriptEntry[]): TranscriptEntry[] {
  const byUuid = new Map(entries.map(e => [e.uuid, e]))
  const chain = []
  let currentUuid = entries[entries.length - 1].uuid
  while (currentUuid) {
    const e = byUuid.get(currentUuid)
    if (!e) break
    chain.push(e)
    currentUuid = e.parentUuid
  }
  chain.reverse()
  return chain
}
```

### 4.4 消息删除时的链修复

```typescript
// 当删除消息 (tombstone/compact 等) 时, 需要修复 parentUuid 链
// 避免 dangling pointer — survivor 的 parentUuid 指向已删除消息

function deleteAndRelinkMessages(
  toDelete: Set<UUID>,
  messages: Map<UUID, TranscriptEntry>,
): void {
  // 1. 记录每条待删除消息的 parentUuid
  const deletedParent = new Map<UUID, UUID | null>()
  for (const uuid of toDelete) {
    deletedParent.set(uuid, messages.get(uuid)?.parentUuid)
  }

  // 2. 删除
  for (const uuid of toDelete) {
    messages.delete(uuid)
  }

  // 3. 重新链接: 对每个 parentUuid 指向已删除消息的 survivor
  //    沿 deletedParent 链上溯直到找到存活消息或 null
  for (const [uuid, msg] of messages) {
    if (!msg.parentUuid || !toDelete.has(msg.parentUuid)) continue
    messages.set(uuid, {
      ...msg,
      parentUuid: resolve(msg.parentUuid),  // walk upward
    })
  }
}
```

### 4.5 循环检测

```typescript
// 在链 walk 中检测 parentUuid 循环:
while (visited.size < maxDepth) {
  if (visited.has(currentMsg.uuid)) {
    // 检测到循环 → 返回部分 transcript
    logForDebugging(
      `Cycle detected in parentUuid chain at message ${currentMsg.uuid}.`
    )
    return partialTranscript
  }
}
```

---

## 5. Message 类型体系

(从 `src/utils/messages.ts` 的 import 推断 — `src/types/message.ts` 在快照中不存在，可能为构建时生成)

```typescript
// 核心消息类型:
type Message =
  | UserMessage
  | AssistantMessage
  | SystemMessage
  | AttachmentMessage
  | ProgressMessage

// 用户消息 (用户输入 或 tool_result):
type UserMessage = {
  type: 'user'
  uuid: UUID
  parentUuid: UUID | null
  content: string | ContentBlockParam[]  // 文本或 Anthropic content blocks
  toolUseResult?: string                  // tool_result 文本摘要
  sourceToolAssistantUUID?: UUID          // 工具结果的来源 assistant
  isMeta?: boolean                        // 元数据消息 (不显示)
  isCompactSummary?: boolean              // 压缩摘要
}

// 助手消息 (模型响应):
type AssistantMessage = {
  type: 'assistant'
  uuid: UUID
  parentUuid: UUID | null
  message: {
    model: string
    content: ContentBlockParam[]  // text | tool_use | thinking | redacted_thinking
    stop_reason: 'end_turn' | 'tool_use' | 'max_tokens' | 'stop_sequence'
    usage: Usage  // { input_tokens, output_tokens, cache_read_input_tokens, ... }
  }
  apiError?: string  // API 错误信息 (如果调用失败)
}

// 系统消息 (边界标记、通知等):
type SystemMessage = {
  type: 'system'
  uuid: UUID
  parentUuid: UUID | null
  content: string
  compactBoundary?: boolean     // 压缩边界标记
  microcompactBoundary?: boolean // 微压缩边界标记
  level?: SystemMessageLevel    // 'info' | 'warning' | 'error'
}

// 子类型:
type SystemCompactBoundaryMessage = SystemMessage & { compactBoundary: true }
type SystemMicrocompactBoundaryMessage = SystemMessage & { microcompactBoundary: true }
type SystemAPIErrorMessage = SystemMessage & { apiError: string }
type SystemInformationalMessage = SystemMessage & { level: 'info' }
type SystemStopHookSummaryMessage = SystemMessage   // stop hook 摘要
type SystemTurnDurationMessage = SystemMessage      // 轮次持续时间
type SystemBridgeStatusMessage = SystemMessage      // 桥接状态
type SystemMemorySavedMessage = SystemMessage       // 记忆保存通知
type SystemAgentsKilledMessage = SystemMessage      // 子代理被杀死
type SystemApiMetricsMessage = SystemMessage        // API 指标
type SystemPermissionRetryMessage = SystemMessage   // 权限重试
type SystemAwaySummaryMessage = SystemMessage       // 离开摘要
type SystemScheduledTaskFireMessage = SystemMessage  // 定时任务触发
type SystemLocalCommandMessage = SystemMessage      // 本地命令输出

// 附件消息:
type AttachmentMessage = {
  type: 'attachment'
  uuid: UUID
  parentUuid: UUID | null
  content: string
  // attachment 元数据 (来自 src/utils/attachments.ts 的 Attachment 类型)
}

// 进度消息:
type ProgressMessage = {
  type: 'progress'
  // 临时 UI 状态 — 不参与 parentUuid 链
  // 不持久化到 JSONL
}

// 墓碑消息 (标记被删除/压缩的消息):
type TombstoneMessage = {
  type: 'tombstone'
  // 用于标记消息已被移除
}

// 工具使用摘要 (大量 tool_use 的批处理):
type ToolUseSummaryMessage = {
  type: 'tool_use_summary'
  // 多个 tool_use 的合并摘要
}

// 流事件:
type StreamEvent = {
  type: 'stream_event'
  // API 流式响应的单个 delta
}

// 请求开始事件:
type RequestStartEvent = {
  type: 'request_start'
  // API 调用开始通知 (SDK 用途)
}
```

### 5.1 消息的序列化字段

所有 TranscriptMessage 在写入 JSONL 时附加:

```typescript
type SerializedMessage = Message & {
  cwd: string           // 当前工作目录 (绝对路径)
  userType: string      // USER_TYPE 环境变量: 'external' | 'internal' | ...
  entrypoint?: string   // CLAUDE_CODE_ENTRYPOINT: 'cli' | 'sdk-ts' | 'sdk-py' | ...
  sessionId: string     // 会话 ID
  timestamp: string     // ISO 8601 时间戳
  version: string       // Claude Code 版本号
  gitBranch?: string    // Git 分支 (写入时的快照)
  slug?: string         // Session slug (用于 --resume)
}
```

---

## 6. Project 类 — 持久化引擎

### 6.1 架构

```typescript
// src/utils/sessionStorage.ts
class Project {
  // ── 会话缓存 ──
  currentSessionTag: string | undefined
  currentSessionTitle: string | undefined       // customTitle
  currentSessionAgentName: string | undefined   // /rename
  currentSessionAgentColor: string | undefined
  currentSessionLastPrompt: string | undefined  // --resume 使用
  currentSessionAgentSetting: string | undefined // --agent flag
  currentSessionMode: 'coordinator' | 'normal' | undefined
  currentSessionWorktree: PersistedWorktreeSession | null | undefined
  currentSessionPrNumber: number | undefined    // PR 信息
  currentSessionPrUrl: string | undefined
  currentSessionPrRepository: string | undefined

  // ── 写入引擎 ──
  sessionFile: string | null = null          // 惰性初始化
  private pendingEntries: Entry[] = []       // sessionFile=null 时的缓冲区
  private pendingWriteCount: number = 0
  private flushResolvers: Array<() => void> = []
  private writeQueues: Map<string, Array<{
    entry: Entry
    resolve: () => void
  }>>
  private flushTimer: ReturnType<typeof setTimeout> | null = null

  // ── 远程 ──
  private remoteIngressUrl: string | null = null        // CCR v1
  private internalEventWriter: InternalEventWriter | null  // CCR v2
  private internalEventReader: InternalEventReader | null
  private internalSubagentEventReader: InternalEventReader | null

  // ── 配置 ──
  private FLUSH_INTERVAL_MS = 100           // 批量 flush 间隔
  private readonly MAX_CHUNK_BYTES = 100 * 1024 * 1024  // 100MB 分块
}
```

### 6.2 写入管线

```
recordTranscript(messages)
  ↓
insertMessageChain(messages, startingParentUuid)
  ↓ (每条消息)
  ├── 跳过 progress 消息
  ├── dedup: 已持久化 → 跳过但更新 parentUuid 游标
  ├── 序列化: Message → SerializedMessage (追加 cwd/timestamp/sessionId/...)
  └── enqueueWrite(transcriptPath, serializedEntry)
      ↓
  scheduleDrain()  ← setTimeout(100ms)
      ↓
  drainWriteQueue()
      ├── 按文件分组队列
      ├── 每个队列: 批处理序列化 → JSON 字符串
      ├── 块大小检查 (100MB 分块)
      ├── fsAppendFile (mode 0o600)
      └── resolve 每个条目的 promise
```

### 6.3 惰性初始化 (materializeSessionFile)

```typescript
// Session file 在第一条 user/assistant 消息到达时才创建
// 防止创建只有 metadata 的空 session 文件
// pendingEntries 在 sessionFile=null 时缓冲所有条目

materializeSessionFile(): void
// → 创建项目目录 (如果不存在)
// → 设置 sessionFile 路径
// → flush pendingEntries 到新文件
```

### 6.4 元数据重新追加 (reAppendSessionMetadata)

```typescript
// 在 flush 后调用 — 将 customTitle/tag 等元数据重新追加到 JSONL 尾部
// 确保 readLiteMetadata 的 64KB tail window 中包含这些字段
// 如果 /rename 后积累了足够多的消息, custom-title 会超出 tail window
// → --resume 只显示 auto-generated firstPrompt 而非 customTitle

reAppendSessionMetadata(): void
// → 追加 custom-title, ai-title, tag, agent-name,
//   agent-color, last-prompt, agent-setting, mode, worktree,
//   PR metadata 条目到 JSONL 尾部
```

---

## 7. 会话元数据层 (Metadata Layer)

### 7.1 Metadata 消息类型 (JSONL 中的特殊条目)

```typescript
// 这些条目作为普通 JSONL 行写入, 但携带特殊 type:

// AI 生成的标题:
{ "type": "ai-title", "sessionId": "abc123", "aiTitle": "Building React Components" }

// 用户自定义标题 (/rename):
{ "type": "custom-title", "sessionId": "abc123", "customTitle": "My React Project" }
// 注意: custom-title 始终优先于 ai-title

// 用户标签:
{ "type": "tag", "sessionId": "abc123", "tag": "frontend" }

// Agent 名称 (/rename):
{ "type": "agent-name", "sessionId": "abc123", "agentName": "React Builder" }

// Agent 颜色:
{ "type": "agent-color", "sessionId": "abc123", "agentColor": "blue" }

// 最近用户提示词 (--resume 显示):
{ "type": "last-prompt", "sessionId": "abc123", "lastPrompt": "Build a component..." }

// Agent 设置 (--agent flag):
{ "type": "agent-setting", "sessionId": "abc123", "agentSetting": "coder" }

// 会话模式:
{ "type": "mode", "sessionId": "abc123", "mode": "coordinator" }

// Worktree 状态:
{ "type": "worktree-session", "sessionId": "abc123",
  "worktree": { "path": "/tmp/...", "branch": "fix-bug", "name": "my-worktree" } | null }

// AI 摘要:
{ "type": "summary", "sessionId": "abc123", "leafUuid": "...", "summary": "..." }

// 任务摘要 (用于 claude ps):
{ "type": "task-summary", "sessionId": "abc123", "summary": "...", "timestamp": "..." }

// 文件历史快照:
{ "type": "file-history-snapshot", ... }

// 归属快照:
{ "type": "attribution-snapshot", ... }

// 上下文折叠:
{ "type": "context-collapse-commit", ... }
{ "type": "context-collapse-snapshot", ... }
```

### 7.2 轻量读取 (readLiteMetadata)

```typescript
// src/utils/sessionStoragePortable.ts
// 通过只读 JSONL 文件的最后 LITE_READ_BUF_SIZE (64KB) 字节
// 并使用字符串匹配 (非完整 JSON 解析) 提取关键字段:
// - customTitle / aiTitle / firstPrompt
// - tag / agentName / agentColor
// - sessionId / lastPrompt
// 不加载完整消息历史 → 快速列出所有会话

readLiteMetadata(projectDir: string): LogOption[]
```

### 7.3 完整读取

```typescript
// 加载完整 transcript (受 50MB 限制):
loadTranscriptFile(sessionFile: string): Transcript
  ├── 逐行 JSON 解析
  ├── 跳过 isEphemeralToolProgress 条目
  ├── 修复 legacy progress 的 UUID 链 (progressBridge)
  ├── 检查 MAX_TRANSCRIPT_READ_BYTES (50MB) → 超过则 bail out
  └── 返回 TranscriptMessage[] (validated)
```

---

## 8. 会话搜索与列表 (LogOption)

### 8.1 LogOption 数据结构

```typescript
type LogOption = {
  date: string                       // 会话日期 (格式化的 started_at)
  messages: SerializedMessage[]      // 所有消息 (lite 模式为 [])
  fullPath?: string                  // JSONL 文件完整路径
  value: number                      // started_at timestamp
  created: Date
  modified: Date
  firstPrompt: string                // 第一个有意义的用户提示词 (200 chars)
  messageCount: number               // 消息总数
  fileSize?: number                  // 文件大小 (bytes)
  isSidechain: boolean               // 是否子代理/侧链会话
  isLite?: boolean                   // 是否轻量模式 (消息未加载)
  sessionId?: string
  teamName?: string                  // 团队名 (swarm)
  agentName?: string                 // Agent 自定义名
  agentColor?: string                // Agent 颜色
  agentSetting?: string              // Agent 定义
  isTeammate?: boolean               // 是否 teammate 创建
  leafUuid?: UUID                    // 链末消息 UUID
  summary?: string                   // 会话摘要
  customTitle?: string               // 用户自定义标题
  tag?: string                       // 标签
  fileHistorySnapshots?: FileHistorySnapshot[]
  attributionSnapshots?: AttributionSnapshotMessage[]
  contextCollapseCommits?: ContextCollapseCommitEntry[]
  contextCollapseSnapshot?: ContextCollapseSnapshotEntry
  gitBranch?: string                 // 结束时的 git 分支
  projectPath?: string               // 原始项目路径
  prNumber?: number                  // PR 编号
  prUrl?: string                     // PR URL
  prRepository?: string              // PR 仓库
  mode?: 'coordinator' | 'normal'
  worktreeSession?: PersistedWorktreeSession | null
  contentReplacements?: ContentReplacementRecord[]
}
```

### 8.2 会话列表实现

```typescript
// 遍历 projects/<slug>/ 下所有 .jsonl 文件
// 对每个文件:
//   1. 提取 firstPrompt (从文件头部)
//   2. 提取 metadata (从文件尾部 64KB)
//   3. 读取文件状态 (大小, 修改时间)
//   4. 构建 LogOption
// 按 started_at 降序排列
```

---

## 9. 子代理会话存储

### 9.1 子代理 Transcript 路径

```typescript
// 子代理 transcript 不与主 transcript 混合
// 存储在单独的 agent-<agentId>.jsonl 文件中

export function getAgentTranscriptPath(agentId: AgentId): string {
  const projectDir = getSessionProjectDir() ?? getProjectDir(getOriginalCwd())
  const sessionId = getSessionId()
  const subdir = agentTranscriptSubdirs.get(agentId)
  const base = subdir
    ? join(projectDir, sessionId, 'subagents', subdir)
    : join(projectDir, sessionId, 'subagents')
  return join(base, `agent-${agentId}.jsonl`)
}
```

### 9.2 Agent Metadata (侧车文件)

```typescript
// agent-<agentId>.meta.json — 不与 JSONL 混合
type AgentMetadata = {
  agentType: string         // 子代理类型 (explore, plan, general-purpose, ...)
  worktreePath?: string     // worktree 隔离路径
  description?: string      // 原始任务描述 (来自 AgentTool input)
}

// 用途: resume 时正确路由子代理类型
// 没有此文件 → fork resume 降级为 general-purpose (4KB system prompt, 无历史)
```

### 9.3 Remote Agent 元数据

```typescript
// remote-agent-<taskId>.meta.json
type RemoteAgentMetadata = {
  taskId: string
  remoteTaskType: string
  sessionId: string          // CCR session ID
  title: string
  command: string
  spawnedAt: number
  toolUseId?: string
  isLongRunning?: boolean
  isUltraplan?: boolean
  isRemoteReview?: boolean
  remoteTaskMetadata?: Record<string, unknown>
}
```

---

## 10. 会话切换与恢复 (Resume)

### 10.1 会话切换流程

```
switchActiveSession(newSessionId, options):
│
├── 1. flushSessionStorage()
│   └── project.flush() + reAppendSessionMetadata()
│
├── 2. switchSession(newSessionId)
│   ├── 更新 bootstrap state 中的 sessionId
│   ├── 更新 sessionProjectDir (如果需要)
│   └── 清除会话级缓存
│
└── 3. hydrateSessionFrom...()
    ├── hydrateFromRemote() — CCR v1
    ├── hydrateFromCCRv2InternalEvents() — CCR v2
    └── 从磁盘 JSONL 加载 — 本地模式
```

### 10.2 Resume 流程

```
--resume <sessionIdOrPrefix>:
│
├── 1. 找到目标 session
│   └── listSessions() → 按前缀匹配
│
├── 2. loadTranscriptFile(sessionFile)
│   ├── 解析 JSONL → Messages[]
│   ├── 跳过 progress 条目
│   ├── 修复 legacy progress 的 UUID 链
│   └── 检查文件大小 (< 50MB)
│
├── 3. switchActiveSession(targetSessionId)
│   └── (见 10.1)
│
├── 4. 恢复状态
│   ├── 恢复 fileHistory (如果 snapshot 存在)
│   ├── 恢复 contentReplacements
│   ├── 恢复 contextCollapse 状态
│   ├── 恢复 worktreeSession
│   └── 恢复 subagent/remote agent 任务
│
└── 5. 构建 QueryEngine(initialMessages: messages)
    └── 从恢复的消息继续
```

---

## 11. Compaction 对会话存储的影响

### 11.1 压缩边界标记

```typescript
// 每次 major compaction 后插入 SystemCompactBoundaryMessage:
{
  "type": "system",
  "compactBoundary": true,
  "uuid": "...",
  "parentUuid": null,     // ← 新根! 打破链
  "logicalParentUuid": "..."  // ← 保留逻辑链供重建
}

// 使用:
getMessagesAfterCompactBoundary(messages)
// → 返回最后一个 compact boundary 之后的所有消息
// → API 调用时只发送这部分 (节省 token)
```

### 11.2 MicroCompact 对存储的影响

```typescript
// MicroCompact 在消息内容中做原地替换:
// 旧 tool result 内容 → "[Old tool result content cleared]"
// 不创建新消息, 不改变 UUID 链
// 仅影响发送到 API 的消息内容 (normalizeMessagesForAPI)

// 可压缩的工具:
COMPACTABLE_TOOLS = [
  'read_file', 'bash', 'grep', 'glob',
  'web_search', 'web_fetch', 'edit', 'write'
]
```

### 11.3 Tombstone 消息

```typescript
// 当消息被完全删除 (而非压缩) 时创建 tombstone:
type TombstoneMessage = {
  type: 'tombstone'
  uuid: UUID          // 被删除消息的 UUID
}

// 删除 + 重新链接 UUID 链:
deleteAndRelinkMessages(toDelete: Set<UUID>, messages: Map<UUID, Entry>)
```

---

## 12. FileHistory — 文件历史与撤销

### 12.1 核心设计

```typescript
// src/utils/fileHistory.ts
type FileHistoryState = {
  snapshots: Map<string, FileSnapshot[]>  // filePath → 快照列表
  undo: Map<string, FileSnapshot[]>       // 可撤销状态
}

type FileHistorySnapshot = {
  path: string
  content: string              // 写文件前的内容
  timestamp: number
  toolName: string             // 哪个工具做了修改
  sessionId: string
}
```

### 12.2 快照时机

```typescript
// FileWriteTool / FileEditTool 执行前:
fileHistoryMakeSnapshot(filePath, toolName)
// → 保存当前文件内容到 snapshots
// → 支持 undo 操作恢复
```

---

## 13. 与 mochagent 的对比与建议

### 13.1 核心差异

| 维度 | Claude Code | mochagent (当前) |
|------|-------------|-----------------|
| **存储格式** | JSONL per session | JSONL per session ✅ |
| **UUID 链** | ✅ parentUuid 树状链 | ✅ parentUuid (SessionStore) |
| **Metadata** | JSONL 行内 special type | .meta.json 侧车文件 |
| **写入模式** | 批量缓冲 (100ms) + 100MB 分块 | 同步 per-message append |
| **Dedup** | ✅ insertMessageChain 有 dedup | ❌ 无 |
| **删除+relink** | ✅ deleteAndRelinkMessages | ❌ 无 |
| **Compaction** | 4层: micro/auto/reactive/collapse | 1层: SNIP/MICRO/COLLAPSE |
| **子代理存储** | 独立 agent-{id}.jsonl | 所有在同一个 transcript |
| **轻量读取** | readLiteMetadata (64KB tail) | 完整加载 |
| **Session 缓存** | Project 类 (内存缓存 metadata) | MemoryManager 内嵌 |
| **循环检测** | ✅ UUID 链上有循环检测 | ❌ 无 |
| **Worktree** | ✅ PersistedWorktreeSession | ❌ 无 |
| **文件历史** | ✅ write 前快照 → undo | ❌ FileHistory (basic) |
| **PR 链接** | ✅ 关联到 GitHub PR | ❌ 无 |

### 13.2 优先建议

1. **轻量读取 (readLiteMetadata)**: 实现 tail-only metadata 提取, 不加载完整 transcript 就能列出所有会话。这解决了 mochagent 当前 `listSessions()` 需要完整加载每个 .meta.json 的问题

2. **批量缓冲写入**: 实现类似 `Project` 类的写入队列: 100ms 缓冲 + 批量 append, 减少 IO

3. **dedup + relink**: 在消息插入时添加 dedup 逻辑 (已持久化消息不重复写入), 在压缩/删除时修复 parentUuid 链

4. **子代理 transcript 隔离**: 将 subagent/task 对话写入独立 JSONL 文件, 而非与父会话混合

5. **compactBoundary 机制**: 压缩后插入 boundary 标记 (自身 parentUuid=null 但记录 logicalParentUuid), API 调用时只发送 boundary 之后的消息

6. **Worktree session 持久化**: 记录 worktree 进入/退出状态到 session metadata, 支持 resume 后自动恢复

7. **循环检测**: 在 UUID 链 walk 时添加最大深度守卫 + visited set

---

> **注**: mochagent 的 `SessionStore` 已正确实现了 UUID 链 (parentUuid + buildConversationChain),
> 但在 dedup、relink、轻量读取、子代理隔离和压缩边界方面存在差距。
