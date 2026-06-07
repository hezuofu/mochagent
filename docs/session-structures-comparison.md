# Claude Code vs hermes-agent — 会话与对话结构对比

> 一个请求 (`build a React app`) 在这两个框架中的完整数据结构

---

## 1. 会话对象 (Session)

```
┌──────────────────────────────────────────────────────────┐
│ Claude Code                   │ hermes-agent             │
├───────────────────────────────┼──────────────────────────┤
│ 无统一 Session 对象            │ sessions 表 (SQLite)      │
│ 数据分散在:                    │                          │
│  • bootstrap state            │ id: TEXT PK              │
│    sessionId: SessionId       │ source: TEXT (cli/      │
│    parentSessionId: SessionId │   telegram/discord/...)  │
│  • JSONL metadata rows        │ user_id: TEXT            │
│  • Project.currentSession*    │ model: TEXT              │
│                               │ model_config: JSON       │
│                               │ system_prompt: TEXT      │
│                               │ parent_session_id: TEXT  │
│                               │ started_at: REAL         │
│                               │ ended_at: REAL           │
│                               │ end_reason: TEXT         │
│                               │ message_count: INT       │
│                               │ tool_call_count: INT     │
│                               │ input_tokens: INT        │
│                               │ output_tokens: INT       │
│                               │ cache_read_tokens: INT   │
│                               │ cache_write_tokens: INT  │
│                               │ reasoning_tokens: INT    │
│                               │ cwd: TEXT                │
│                               │ billing_provider: TEXT   │
│                               │ estimated_cost_usd: REAL │
│                               │ actual_cost_usd: REAL    │
│                               │ title: TEXT              │
│                               │ handoff_state: TEXT      │
│                               │ rewind_count: INT        │
│                               │ archived: INT            │
├───────────────────────────────┼──────────────────────────┤
│ 会话元数据: JSONL 行内         │ 会话元数据: SQLite 列     │
│ {"type":"custom-title",        │ UPDATE sessions         │
│  "customTitle":"My App"}       │ SET title = 'My App'    │
│ {"type":"ai-title",            │                          │
│  "aiTitle":"React Setup"}      │                          │
│ {"type":"tag","tag":"frontend"}│                          │
│ {"type":"agent-name",          │                          │
│  "agentName":"React Builder"}  │                          │
├───────────────────────────────┼──────────────────────────┤
│ 会话链:                        │ 会话链:                   │
│ parentSessionId in bootstrap   │ parent_session_id FK     │
│ (string brand)                 │ (SQL foreign key)        │
│                                │ + recursive CTE for tip  │
└───────────────────────────────┴──────────────────────────┘
```

---

## 2. 单条消息 (Message)

```
一个 "user asks + assistant replies with tool + tool result" 的完整轮次:

┌──────────────────────────────────────────────────────────────────┐
│ Claude Code                        │ hermes-agent                │
├────────────────────────────────────┼─────────────────────────────┤
│                                    │                              │
│ UserMessage {                      │ messages 表行:               │
│   type: 'user'                     │   id: 1 (AUTOINCREMENT)     │
│   uuid: "aaa-bbb-ccc"              │   session_id: "sess_001"    │
│   parentUuid: null ← 根节点        │   role: "user"              │
│   sessionId: "sess_001"            │   content: "Build a React   │
│   cwd: "/home/project"             │     component library"      │
│   timestamp: "2026-06-07T..."      │   tool_calls: null          │
│   version: "2.1.168"               │   tool_call_id: null        │
│   userType: "external"             │   timestamp: 1717768825.5   │
│   entrypoint: "cli"                │   active: 1                 │
│   content: "Build a React          │   observed: 0               │
│     component library"             │   token_count: 15           │
│ }                                  │   finish_reason: null       │
│                                    │ }                            │
├────────────────────────────────────┼─────────────────────────────┤
│                                    │                              │
│ AssistantMessage {                 │ messages 表行:               │
│   type: 'assistant'                │   id: 2 (AUTOINCREMENT)     │
│   uuid: "bbb-ccc-ddd"              │   session_id: "sess_001"    │
│   parentUuid: "aaa-bbb-ccc" ← 链   │   role: "assistant"         │
│   message: {                       │   content: "I'll set up..." │
│     model: "claude-sonnet-4-6"     │   tool_calls: [{            │
│     content: [                     │     "id": "toolu_01",       │
│       {type:"text",                │     "type": "function",     │
│        text:"I'll set up..."}      │     "function": {           │
│       {type:"tool_use",            │       "name": "bash",       │
│        id:"toolu_01",              │       "arguments":          │
│        name:"bash",                │         "{...}" }]}         │
│        input:{command:"npm init"}} │   tool_call_id: null        │
│     ]                              │   timestamp: 1717768830.0   │
│     stop_reason: "tool_use"        │   active: 1                 │
│     usage: {                       │   finish_reason: "tool_use" │
│       input_tokens: 5000           │   reasoning: "The user..."  │
│       output_tokens: 500           │   reasoning_content: "..."  │
│       cache_read_input_tokens:3000 │   token_count: 5500         │
│     }                              │ }                            │
│   }                                │                              │
│ }                                  │                              │
├────────────────────────────────────┼─────────────────────────────┤
│                                    │                              │
│ UserMessage (as tool_result) {     │ messages 表行:               │
│   type: 'user'                     │   id: 3 (AUTOINCREMENT)     │
│   uuid: "ccc-ddd-eee"              │   session_id: "sess_001"    │
│   parentUuid: "bbb-ccc-ddd" ← 链   │   role: "tool"              │
│   content: [{                      │   content: "Wrote to        │
│     type:"tool_result",            │     package.json..."        │
│     tool_use_id:"toolu_01",        │   tool_calls: null          │
│     content:"Wrote package.json",  │   tool_call_id: "toolu_01"  │
│     is_error: false                │   tool_name: "bash"         │
│   }]                               │   timestamp: 1717768832.0   │
│   toolUseResult: "Wrote to..."     │   active: 1                 │
│   sourceToolAssistantUUID:         │   observed: 0               │
│     "bbb-ccc-ddd"                  │   token_count: 5            │
│ }                                  │   finish_reason: null       │
│                                    │ }                            │
└────────────────────────────────────┴─────────────────────────────┘
```

---

## 3. 消息链模型

```
Claude Code: parentUuid 树状链             hermes-agent: session_id + id 线性链

  root                                      sessions.sess_001
  │  user_msg_1 (uu=aaa, pu=null)          │
  ▼                                         ├─ msg id=1 role=user
  asst_msg_1 (uu=bbb, pu=aaa)              ├─ msg id=2 role=assistant
  │                                         ├─ msg id=3 role=tool
  ▼                                         ├─ msg id=4 role=assistant
  tool_res_1 (uu=ccc, pu=bbb)              │
  │                                         ORDER BY id ← 真插入顺序
  ▼                                         (非 timestamp, 防 WSL2 时钟漂移)
  asst_msg_2 (uu=ddd, pu=ccc)
  │                                         active=0 ← 软删除
  ▼                                         (rewind: UPDATE SET active=0)
  compactBoundary (uu=eee, pu=null! ← 新根)
  │
  ▼
  asst_msg_3 (uu=fff, pu=eee)

  链操作:                                   链操作:
  buildConversationChain:                  get_messages(session_id):
    Map<UUID→Msg> leaf→root walk+reverse     SELECT ... WHERE active=1 ORDER BY id
  deleteAndRelinkMessages:                 replace_messages:
    删除 + 幸存者 parentUuid 上溯重连         DELETE + INSERT (单事务)
  compactBoundary → 新根 (parentUuid=null)  新 session 行 (parent_session_id)
```

---

## 4. 会话存储文件布局

```
Claude Code                           hermes-agent
─────────────────────                 ─────────────
~/.claude/                            ~/.hermes/
├── projects/                         ├── state.db ← 单一 SQLite (所有会话)
│   └── <projectSlug>/                │   ├── sessions
│       ├── <sessionId>.jsonl ← 转录   │   ├── messages
│       ├── <sessionId>.meta.json      │   │   ├── messages_fts ← FTS5
│       └── <sessionId>/              │   │   └── messages_fts_trigram
│           ├── subagents/            │   ├── schema_version
│           │   └── agent-<id>.jsonl  │   ├── state_meta
│           └── remote-agents/        │   └── compression_locks
│               └── ...meta.json      │
├── settings.json                     ├── projects/  ← 旧格式 (JSONL)
├── credentials.json                  │   └── <hash>/
└── plugins/                          │       └── <sessionId>.jsonl
                                      ├── skills/
                                      ├── plugins/
                                      └── logs/
```

---

## 5. 消息的多模态表示

```
┌───────────────────────────────────────────────────────────────┐
│ Claude Code                  │ hermes-agent                  │
├──────────────────────────────┼───────────────────────────────┤
│                               │                                │
│ Anthropic content blocks      │ 编码技巧:                       │
│ (原生 JSON):                  │ content 列存字符串,              │
│                               │ 多模态用 NUL 前缀标记:          │
│ [                             │                                │
│   {"type":"text",             │ "\x00json:[                    │
│    "text":"I see:"},          │   {\"type\":\"text\",          │
│   {"type":"image",            │    \"text\":\"I see:\"},       │
│    "source":{                 │   {\"type\":\"image_url\",     │
│      "type":"base64",         │    \"image_url\":{             │
│      "data":"iVBOR...",       │     \"url\":\"data:image/      │
│      "media_type":"image/png" │      png;base64,iVBOR...\"}}   │
│    }}                         │ ]"                             │
│ ]                             │                                │
│                               │ _encode_content() 写入前        │
│                               │ _decode_content() 读取后        │
│                               │                                │
│ 工具调用 (tool_use):           │ 工具调用 (tool_calls):          │
│ content block in assistant    │ JSON 字符串列:                  │
│ message, 带 id+name+input     │ tool_calls: "[{                │
│                               │   \"id\":\"toolu_01\",         │
│                               │   \"type\":\"function\",       │
│                               │   \"function\":{               │
│                               │     \"name\":\"bash\",         │
│                               │     \"arguments\":\"{...}\"}}]"│
└───────────────────────────────┴────────────────────────────────┘
```

---

## 6. 消息总数 / 会话列表

```
Claude Code:                          hermes-agent:
  LogOption {                           SessionMeta / SessionSearchResult {
    date: "2026-06-07"                    id: "sess_001"
    messages: SerializedMessage[]         userId: "user_abc"
    firstPrompt: "Build a React..."      startedAt: Instant
    messageCount: 42                      lastActiveAt: Instant
    fileSize: 524288                      metaFile: Path
    sessionId: "sess_001"                messageCount: 42
    customTitle: "My React App"           title: "My React App"
    tag: "frontend"                     }
    agentName: "React Builder"
    isSidechain: false                 SessionSearchResult {
    gitBranch: "main"                    sessionId: "sess_001"
    projectPath: "/home/project"         title: "My React App"
    prNumber: 42                         startedAt: Instant
    summary: "Built React component..."  matches: ["...React...",
    leafUuid: "fff-..."                          "...component..."]
  }                                    }

  提取方式:                             提取方式:
  readSessionLite(file):               sessionStore.listSessions(cwd):
    fd = open(file)                      SELECT ... FROM sessions
    head = read(fd, 0, 64KB)             + correlated subqueries
    tail = read(fd, size-64KB, 64KB)     + recursive CTE (压缩链)
    extractFirstPromptFromHead(head)    sessionStore.searchSessions:
    extractJsonStringField(tail,          FTS5 MATCH or LIKE scan
      "customTitle")
```

---

## 7. 压缩对消息的影响

```
Claude Code:                                hermes-agent:

  Compact Boundary (同一文件内):              Session Rotation (新行):
  
  压缩前:                                    压缩前:
  ... msg_N ...                               sessions: sess_001
                                              messages: msg_1...msg_100
  压缩后:                                    压缩后:
  ... [SUMMARY_PREFIX + 摘要] ...             sessions:
  SystemCompactBoundaryMessage {                sess_001: ended_at=now,
    type: 'system'                                end_reason='compression'
    uuid: eee                                sess_002: parent_session_id=
    parentUuid: null ← 重设                       'sess_001'
    logicalParentUuid: ddd ← 保留            messages: 摘要消息
    compactBoundary: true
  }
  ... protected_tail_msgs ...

  API 调用:                                   API 调用:
  getMessagesAfterCompactBoundary()          直接 SELECT sess_002 的 messages
  → 只发送 boundary 后的消息
```

---

## 8. 关键差异总结

| 维度 | Claude Code | hermes-agent |
|------|-------------|-------------|
| **消息ID** | UUID 字符串 | AUTOINCREMENT 整数 |
| **消息链** | parentUuid 树状 (支持分支/删除/重连) | session_id + id 线性 |
| **存储引擎** | JSONL 文件 (每会话一个) | SQLite 中心 DB (所有会话) |
| **会话元数据** | JSONL 行内 special type | sessions 表列 + FK |
| **全文搜索** | ❌ (需要 grep 文件) | ✅ FTS5 + trigram (CJK) |
| **消息操作** | Tombstone + UUID relink | active=0 软删除 + SELECT WHERE |
| **多模态** | 原生 JSON content blocks | NUL 前缀编码技巧 |
| **压缩** | compactBoundary (同文件) | session rotation (新行) |
| **子代理** | 独立 JSONL 文件 | 同表不同 session_id |
| **Schema演进** | 无 Schema (JSON 自描述) | 声明式 reconcile columns |
| **并发** | 文件 append (无锁) | WAL + jitter retry + 压缩锁 |
| **会话列表** | readSessionLite (64KB tail) | SQL 递归 CTE |
| **链恢复** | leaf→root walk + reverse | ORDER BY id |
