# hermes-agent vs Claude Code — 会话对话存储对比

> 分析日期: 2026-06-07

---

## 目录

1. [总览对比表](#1-总览对比表)
2. [存储架构对比](#2-存储架构对比)
3. [物理存储格式对比](#3-物理存储格式对比)
4. [消息链模型对比](#4-消息链模型对比)
5. [会话读写流程对比](#5-会话读写流程对比)
6. [消息操作对比](#6-消息操作对比)
7. [子代理/分支存储对比](#7-子代理分支存储对比)
8. [压缩对存储的影响对比](#8-压缩对存储的影响对比)
9. [搜索与列表对比](#9-搜索与列表对比)
10. [Schema 演进对比](#10-schema-演进对比)
11. [对 mochagent 的评估与建议](#11-对-mochagent-的评估与建议)

---

## 1. 总览对比表

| 维度 | hermes-agent | Claude Code |
|------|-------------|-------------|
| **存储引擎** | SQLite (`state.db`) | JSONL 文件系统 |
| **会话元数据** | `sessions` 表 (30+ 列) | `{sessionId}.jsonl` + `{sessionId}.meta.json` |
| **消息明细** | `messages` 表 (17 列) | JSONL 行 (每条消息一行) |
| **消息标识** | AUTOINCREMENT `id` (整数) | `uuid` (UUID v4) |
| **消息链** | 时间戳 + AUTOINCREMENT 顺序 | `parentUuid` 链 (树状) |
| **全文搜索** | FTS5 (双索引: 标准 + trigram) | 无内置 (JSONL 全文件 grep) |
| **多模态内容** | `\x00json:` 前缀编码 | 直接 JSON 序列化 |
| **写入模式** | 同步 transaction (BEGIN IMMEDIATE) | 批量缓冲 (100ms) + 100MB 分块 |
| **子代理存储** | 同一 `messages` 表, 不同 `session_id` | 独立 `agent-{agentId}.jsonl` |
| **会话列表** | SQL 查询 (递归 CTE + 压缩链投影) | `readLiteMetadata` (64KB tail scan) |
| **软删除** | `active=0` 标记 | Tombstone + UUID 链 relink |
| **压缩影响** | 创建新 session 行 + `parent_session_id` | `compactBoundary` 消息 (parentUuid=null) |
| **Schema 演进** | 声明式 reconcile + 版本号 | 无 Schema (JSONL 行内容自描述) |
| **并发控制** | WAL + jitter retry + 压缩锁 | 文件 append + lockfile |
| **跨会话搜索** | ✅ FTS5 SQL 查询 | ❌ 无 (需全文件 grep) |
| **远程持久化** | ❌ (仅本地) | ✅ CCR v1/v2 (remote ingress) |
| **计费追踪** | ✅ `sessions` 表内嵌 | ❌ 仅在内存 (bootstrap state) |

---

## 2. 存储架构对比

### 2.1 hermes-agent: 中心化 SQLite

```
~/.hermes/state.db              ← 单一 SQLite 数据库
├── schema_version              ← Schema 版本
├── sessions                    ← 所有会话 (所有项目的所有会话)
├── messages                    ← 所有消息 (所有会话的所有消息)
│   ├── messages_fts            ← 全文搜索索引 (FTS5)
│   └── messages_fts_trigram    ← CJK 子串搜索索引 (trigram FTS5)
├── state_meta                  ← 键值元数据
└── compression_locks           ← 压缩锁 (防竞态)

特点:
• 一个文件 → 跨会话查询天然支持 (JOIN + FTS5)
• 所有项目的所有会话在同一个 DB 中
• 写入需要事务锁 → WAL + jitter retry
```

### 2.2 Claude Code: 分布式 JSONL

```
~/.claude/projects/
├── <projectSlug-1>/            ← 项目 1
│   ├── <sessionId-1>.jsonl     ← 会话 1 转录
│   ├── <sessionId-2>.jsonl     ← 会话 2 转录
│   ├── <sessionId-3>/          ← 会话 3 子目录
│   │   ├── subagents/
│   │   │   ├── agent-<id>.jsonl
│   │   │   └── agent-<id>.meta.json
│   │   └── remote-agents/
│   │       └── remote-agent-<id>.meta.json
│   └── ...
├── <projectSlug-2>/            ← 项目 2
│   └── ...
└── ...

特点:
• 每会话一个 JSONL 文件 → 隔离性好
• 按 projectSlug (cwd hash) 分目录 → 天然项目隔离
• 跨会话搜索需要扫描多个文件
```

---

## 3. 物理存储格式对比

### 3.1 hermes-agent: SQLite 表结构

```sql
-- 会话元数据 (30+ 字段)
CREATE TABLE sessions (
    id TEXT PRIMARY KEY,              -- session UUID
    source TEXT NOT NULL,             -- 'cli'|'telegram'|'discord'|'tui'|'cron'
    user_id TEXT,
    model TEXT,                       -- 模型名
    model_config TEXT,                -- JSON: 完整模型配置快照
    system_prompt TEXT,               -- 完整系统提示词 (prefix cache 恢复)
    parent_session_id TEXT,           -- 父会话ID (压缩链/branch链)
    started_at REAL NOT NULL,
    ended_at REAL,
    end_reason TEXT,                  -- 'compression'|'branched'|'tui_shutdown'
    message_count INTEGER DEFAULT 0,
    tool_call_count INTEGER DEFAULT 0,
    input_tokens INTEGER DEFAULT 0,
    output_tokens INTEGER DEFAULT 0,
    cache_read_tokens INTEGER DEFAULT 0,
    cache_write_tokens INTEGER DEFAULT 0,
    reasoning_tokens INTEGER DEFAULT 0,
    cwd TEXT,                         -- 工作目录
    billing_provider TEXT,
    estimated_cost_usd REAL,
    actual_cost_usd REAL,
    title TEXT,
    handoff_state TEXT,               -- Gateway 离线状态
    rewind_count INTEGER DEFAULT 0,
    archived INTEGER DEFAULT 0,       -- 软归档
    FOREIGN KEY (parent_session_id) REFERENCES sessions(id)
);

-- 消息明细 (17 字段)
CREATE TABLE messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,  -- 整数自增 ID
    session_id TEXT NOT NULL REFERENCES sessions(id),
    role TEXT NOT NULL,                     -- 'user'|'assistant'|'system'|'tool'
    content TEXT,                           -- 支持 \x00json: 前缀的多模态编码
    tool_call_id TEXT,
    tool_calls TEXT,                        -- JSON: [{function: {name, arguments}}]
    tool_name TEXT,
    timestamp REAL NOT NULL,               -- Unix timestamp
    token_count INTEGER,
    finish_reason TEXT,                     -- 'stop'|'length'|'tool_calls'
    reasoning TEXT,                         -- thinking 内容
    reasoning_content TEXT,
    reasoning_details TEXT,                 -- JSON: 推理详情
    codex_reasoning_items TEXT,
    codex_message_items TEXT,
    platform_message_id TEXT,              -- Telegram update_id / 外部平台 ID
    observed INTEGER DEFAULT 0,            -- 用户已读标记
    active INTEGER NOT NULL DEFAULT 1      -- 软删除标记
);
```

### 3.2 Claude Code: JSONL 行格式

```jsonl
{"type":"user","uuid":"a1b2-...","parentUuid":null,"sessionId":"abc123","cwd":"/home/user/project","timestamp":"2026-06-07T10:30:00.000Z","version":"2.1.168","userType":"external","content":"Build a React app"}
{"type":"assistant","uuid":"b2c3-...","parentUuid":"a1b2-...","sessionId":"abc123","message":{"model":"claude-sonnet-4-6","content":[{"type":"text","text":"I'll set up..."},{"type":"tool_use","id":"toolu_01...","name":"bash","input":{"command":"npm init"}}],"usage":{"input_tokens":5000,"output_tokens":500}}}
{"type":"user","uuid":"c3d4-...","parentUuid":"b2c3-...","sessionId":"abc123","content":[{"type":"tool_result","tool_use_id":"toolu_01...","content":"Wrote to package.json"}],"toolUseResult":"Wrote to package.json","sourceToolAssistantUUID":"b2c3-..."}
{"type":"ai-title","sessionId":"abc123","aiTitle":"React Component Setup"}
{"type":"custom-title","sessionId":"abc123","customTitle":"My React App"}
```

### 3.3 核心差异分析

| 特性 | hermes-agent (SQLite) | Claude Code (JSONL) |
|------|----------------------|---------------------|
| **消息 ID** | 整数 AUTOINCREMENT | UUID 字符串 |
| **顺序保证** | AUTOINCREMENT (与时间戳无关) | 文件追加顺序 |
| **多模态** | `\x00json:` 前缀 + JSON 编码 | 直接 JSON 嵌套在 content 中 |
| **元数据** | 独立 `sessions` 表 JOIN | 特殊 type 行内联在 JSONL 尾部 |
| **索引** | B-tree 索引 + FTS5 索引 | 无 (文件系统层面) |
| **查询能力** | 任意 SQL (JOIN, WHERE, LIKE, FTS) | 顺序扫描 / 尾读 |
| **Schema 约束** | 强类型列 + NOT NULL + FOREIGN KEY | 无约束 (松散 JSON) |

---

## 4. 消息链模型对比

### 4.1 hermes-agent: 时间戳 + AUTOINCREMENT

```
messages 表:
┌────┬────────────┬───────┬───────────────┬──────────┐
│ id │ session_id │ role  │ content       │ timestamp│
├────┼────────────┼───────┼───────────────┼──────────┤
│ 1  │ sess_001   │ user  │ "Build..."    │ 1000.0   │
│ 2  │ sess_001   │ asst  │ "I'll..."     │ 1005.0   │
│ 3  │ sess_001   │ tool  │ "Wrote..."    │ 1006.0   │
│ 4  │ sess_001   │ asst  │ "Done!"       │ 1010.0   │
└────┴────────────┴───────┴───────────────┴──────────┘

排序: ORDER BY id (AUTOINCREMENT, 真插入顺序)
      而非 timestamp (WSL2 时钟漂移问题)

优点: 简单, 易理解, 整数索引快
缺点: 不支持树状分支, 无法删除单条消息后重连
```

### 4.2 Claude Code: parentUuid 树状链

```
JSONL 中的 UUID 链:
user_msg_1       (uuid=aaa, parentUuid=null)
  ↓
asst_msg_1       (uuid=bbb, parentUuid=aaa)
  ↓
tool_result_1    (uuid=ccc, parentUuid=bbb)
  ↓
asst_msg_2       (uuid=ddd, parentUuid=ccc)
  ↓
compactBoundary  (uuid=eee, parentUuid=null, logicalParentUuid=ddd)  ← 新根!
  ↓
asst_msg_3       (uuid=fff, parentUuid=eee)

恢复流程:
  buildConversationChain(messages, leafMsg):
    1. Map<UUID, Message> byUuid
    2. 从 leaf 沿 parentUuid 反向 walk
    3. 遇到 parentUuid=null → 到达根
    4. reverse → root-first order

删除 + 重连:
  deleteAndRelinkMessages(toDelete, messages):
    1. 记录待删除消息的 parentUuid
    2. 删除消息
    3. 对每个 survivor: 如果其 parentUuid 指向已删除消息
       → 沿 deletedParent 链上溯到存活消息 → relink
    4. 循环检测: visited set + max depth

优点: 支持树状分支/删除/relink, compactBoundary 自然分割
缺点: 必须 walk 链才能获取完整对话, 部分读取需要全链重建
```

### 4.3 链模型对比

| 特性 | hermes-agent (线性) | Claude Code (树状) |
|------|---------------------|---------------------|
| 链类型 | 线性时间序 | UUID 单向链 |
| 分支支持 | ❌ (通过 parent_session_id 做会话级分支) | ✅ 消息级分支 |
| 删除 | soft-delete (active=0) + 不参与 SELECT | 真删除 + UUID 链 relink |
| 压缩 | 新 session 行 | compactBoundary 消息 (逻辑断点) |
| Rewind/Undo | `active=0` → SELECT 排除 | Tombstone + relink |
| 循环防护 | 不适用 (线性) | visited set + max depth |
| 恢复复杂度 | SELECT ... ORDER BY id | buildConversationChain (walk + reverse) |
| 部分读取 | LIMIT + OFFSET | 必须先 walk 到 root 再截取 |

---

## 5. 会话读写流程对比

### 5.1 写入流程

```
hermes-agent (SQLite):
  AIAgent._flush_messages_to_session_db()
    ↓
  SessionDB.append_message(session_id, role, content, ...)
    ↓
  _execute_write(conn → BEGIN IMMEDIATE → INSERT → COMMIT)
    ↓
  SQLite WAL: 先写 WAL, 后台 checkpoint

特点:
• 每条消息独立事务 → 立即持久
• WAL 允许并发读 (reader 不 block writer)
• BEGIN IMMEDIATE + jitter retry 防 write convoy

Claude Code (JSONL):
  recordTranscript(messages)
    ↓
  insertMessageChain(messages, startingParentUuid)
    ├── 跳过 progress
    ├── dedup: UUID 已存在 → 跳过
    ├── Encode: Message → SerializedMessage (追加 cwd/timestamp/...)
    └── enqueueWrite(transcriptPath, entry)
        ↓
  scheduleDrain()  ← setTimeout(100ms)
    ↓
  drainWriteQueue()
    ├── 按文件分组
    ├── 批处理序列化
    ├── 100MB 分块检查
    └── fsAppendFile (mode 0o600)

特点:
• 100ms 批量缓冲 → 减少 IO 次数
• 100MB 分块 → 防止单次写入过大
• UUID dedup → 幂等安全
• 惰性创建 session 文件 (第一条消息才创建)
```

### 5.2 读取流程

```
hermes-agent (SQLite):
  resume session:
    SessionDB.get_messages(session_id)
      ↓
    SELECT * FROM messages
    WHERE session_id = ? AND active = 1
    ORDER BY id
      ↓
    内存中重建 messages list (List[Dict])

Claude Code (JSONL):
  resume session:
    loadTranscriptFile(sessionFile):
      ↓
    逐行 JSON.parse (最多 50MB)
      ↓
    buildConversationChain(messages, leafMsg):
      Map<UUID → Message>
      → 从 leaf 沿 parentUuid walk 到 root
      → reverse → root-first order

  lite 模式 (会话列表):
    readSessionLite(sessionFile):
      ├── 打开 fd, 获取 size
      ├── 读取 head (64KB)
      └── 读取 tail (64KB)
          ├── 从 head 提取 firstPrompt (字符串匹配)
          └── 从 tail 提取 metadata (customTitle, tag, ...)
```

---

## 6. 消息操作对比

### 6.1 CRUD 操作

| 操作 | hermes-agent | Claude Code |
|------|-------------|-------------|
| **写入** | `append_message()` → INSERT | `enqueueWrite()` → 异步批量 append |
| **读取全部** | `get_messages()` → SELECT + ORDER BY | `loadTranscriptFile()` → JSONL parse + chain walk |
| **替换全部** | `replace_messages()` → DELETE + 批量 INSERT (单事务) | 重写 JSONL 文件 |
| **删除单条** | `active=0` (软删除, SELECT 排除) | Tombstone + UUID 链 relink |
| **Undo/Rewind** | `rewind_to_message(id)` → `active=0` WHERE id > N | `deleteAndRelinkMessages(set)` → relink survivor chains |
| **读取窗口** | `get_messages_around(id, window)` → SQL 窗口查询 | 必须先 walk 整个链, 再截取窗口 |
| **读取 bookends** | `get_anchored_view(id, window, bookend)` → 单次 SQL 查询 | 额外逻辑 |

### 6.2 幂等性

```
hermes-agent:
  append_message → INSERT (无 dedup, 重复调用产生重复行)

Claude Code:
  insertMessageChain → UUID dedup:
    if (messageSet.has(entry.uuid)) → skip
    即使用 UUID 在内存 Set 中做幂等检查
```

---

## 7. 子代理/分支存储对比

### 7.1 hermes-agent

```sql
-- 子代理 = 新 sessions 行:
INSERT INTO sessions (id, parent_session_id, source, ...)
VALUES ('sub_agent_001', 'parent_001', 'subagent', ...);

-- 子代理消息在同一 messages 表:
INSERT INTO messages (session_id, role, content, ...)
VALUES ('sub_agent_001', 'user', 'Build component...', ...);

-- 查询: 通过 session_id 区分
SELECT * FROM messages WHERE session_id = 'sub_agent_001';

-- 通过 parent_session_id 查找父会话的所有子代理:
SELECT * FROM sessions WHERE parent_session_id = 'parent_001';
```

### 7.2 Claude Code

```
# 子代理 = 独立 JSONL 文件:
~/.claude/projects/<projectSlug>/
    <parentSessionId>.jsonl                ← 父会话
    <parentSessionId>/subagents/          ← 子代理目录
        agent-<agentId>.jsonl              ← 子代理转录 (独立)
        agent-<agentId>.meta.json          ← 子代理元数据 (侧车文件)
        workflows/<runId>/agent-<agentId>.jsonl  ← workflow 子代理

# AgentMetadata (侧车文件):
{
  "agentType": "general-purpose",
  "worktreePath": "/tmp/claude/worktrees/abc",
  "description": "Build React component library"
}

# UUID 隔离:
# - 子代理 JSONL 有独立的 UUID 链
# - 主会话 messageSet 不追踪子代理 UUID (防止 dangling parentUuid)
# - resume 子代理时从独立文件加载
```

### 7.3 差异分析

| 特性 | hermes-agent | Claude Code |
|------|-------------|-------------|
| 子代理存储 | 同表, 不同 session_id | 独立 JSONL 文件 |
| 链关系 | parent_session_id FK | 目录嵌套 + AgentMetadata |
| 查询复杂度 | SQL JOIN (简单) | 文件查找 (需要路径解析) |
| 隔离性 | 弱 (共享表, 可能误操作) | 强 (物理文件隔离) |
| 恢复 | SELECT WHERE session_id | 读取独立文件 |
| 子代理类型恢复 | session 行有 source 字段 | AgentMetadata.agentType |

---

## 8. 压缩对存储的影响对比

### 8.1 hermes-agent

```
压缩 = Session Rotation:
  1. 旧 session → end_session('compression')
     UPDATE sessions SET ended_at=now, end_reason='compression'
     WHERE id = old_session_id

  2. 新 session → create_session(parent_session_id=old_id)
     INSERT INTO sessions (id, parent_session_id, ...)

  3. 压缩后的 messages 写入新 session
     replace_messages(new_session_id, compacted_messages)

  4. 新会话继承 system_prompt 重建 → rebuild

链: old_session → new_session (parent_session_id = old_id)
get_compression_tip(): 沿 parent_session_id 链找最新 continuation
```

### 8.2 Claude Code

```
压缩 = Compact Boundary:
  1. 生成压缩摘要 (fork agent)
  2. 插入 SystemCompactBoundaryMessage:
     {'type': 'system', 'compactBoundary': true,
      'uuid': eee, 'parentUuid': null,     // ← 新根
      'logicalParentUuid': ddd}            // ← 保留逻辑链

  3. API 调用: getMessagesAfterCompactBoundary()
     → 只发送 boundary 之后的消息 (节省 token)

  4. MicroCompact: 原地替换 tool result 内容
     '[Old tool result content cleared]'
     → 不创建新消息, 不改变 UUID 链

UUID 链:
  ... → ddd → [COMPACT BOUNDARY: eee, parentUuid=null] → fff → ...
```

### 8.3 差异

| 特性 | hermes-agent | Claude Code |
|------|-------------|-------------|
| 方式 | 创建新 session 行 | 插入 boundary 消息 |
| 旧消息 | 保留在旧 session | 保留在同一文件 (但 API 不发送) |
| 链 | parent_session_id FK 链 | parentUuid 树状链 + logicalParentUuid |
| 搜索旧内容 | 需要 JOIN 旧 session | 仍可通过 JSONL 文件访问 |
| 层数 | 1 层 (ContextEngine) | 4 层 (micro/auto/reactive/collapse) |

---

## 9. 搜索与列表对比

### 9.1 会话列表

```
hermes-agent:
  SessionDB.list_sessions_rich(source, exclude_sources, limit, offset, ...)
    ↓
  WITH RECURSIVE chain(root_id, cur_id) AS (
    SELECT id, id FROM sessions WHERE {where}
    UNION ALL
    SELECT root_id, child.id
    FROM chain JOIN sessions parent ON parent.id = cur_id
    JOIN sessions child ON child.parent_session_id = cur_id
    WHERE parent.end_reason = 'compression'
  )
  SELECT ... correlated subqueries for preview + last_active
  ORDER BY effective_last_active DESC

特点:
• 单次 SQL + 递归 CTE 完成
• 压缩链投影: 根会话 → 显示最新 continuation 的信息
• 支持按 source/platform/user_id 过滤

Claude Code:
  listSessions(projectDir):
    ↓
  readdir(projectDir) → 遍历 *.jsonl 文件
    ↓
  readSessionLite(filePath) → 读取 head(64KB) + tail(64KB)
    ├── extractFirstPromptFromHead(head) → 用户第一句 (字符串匹配)
    ├── extractJsonStringField(tail, "customTitle") → 标题
    ├── extractJsonStringField(tail, "aiTitle") → AI 标题
    └── stat → mtime, size

特点:
• 文件扫描 + 尾读 (无需完整加载)
• 不支持压缩链投影 (每个压缩 continuation 是独立文件)
• 按 mtime 排序
```

### 9.2 跨会话搜索

```
hermes-agent:
  ✅ 内置 FTS5 全文搜索
  FTS_SQL: messages_fts(content) + messages_fts_trigram(content, tokenize='trigram')
  触发器自动同步 INSERT/UPDATE/DELETE
  search: SELECT ... FROM messages_fts WHERE content MATCH 'query'

Claude Code:
  ❌ 无内置搜索
  需要 grep 所有 JSONL 文件
  或依赖 session_search tool (fork agent 做搜索)
```

---

## 10. Schema 演进对比

### 10.1 hermes-agent: 声明式

```python
# SCHEMA_SQL 是 single source of truth
SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS sessions (...);
CREATE TABLE IF NOT EXISTS messages (...);
"""

# 自动 reconcile: diff live columns vs SCHEMA_SQL, ADD missing columns
def _reconcile_columns(self, cursor):
    expected = self._parse_schema_columns(SCHEMA_SQL)
    for table_name, declared_cols in expected.items():
        live_cols = cursor.execute(f'PRAGMA table_info("{table_name}")')
        for col_name, col_type in declared_cols.items():
            if col_name not in live_cols:
                cursor.execute(
                    f'ALTER TABLE "{table_name}" ADD COLUMN "{col_name}" {col_type}'
                )

# 数据迁移 (版本门控):
if current_version < 12:
    cursor.execute("UPDATE messages SET active = 1 WHERE active IS NULL")
```

### 10.2 Claude Code: 无 Schema

```
JSONL 格式自描述:
• 每行是独立 JSON → 字段可以随时增删
• 旧字段自然忽略 (向前兼容)
• 新字段自然添加 (向后兼容)
• 没有 ALTER TABLE 操作
• 缺点: 无结构约束, 脏数据可能累积
```

---

## 11. 对 mochagent 的评估与建议

### 11.1 mochagent 当前状态

```
✅ 已有:
  SessionStore (JSONL + parentUuid 链)
  → 基础 UUID 链正确
  → buildConversationChain 正确

❌ 缺失:
  - dedup (插入幂等)
  - deleteAndRelinkMessages (删除后链修复)
  - lite read (64KB tail 快速列表)
  - 子代理独立文件
  - compactBoundary 机制
  - 循环检测
  - FTS 搜索
  - 批量缓冲写入
```

### 11.2 推荐混合方案

```
采用 SQLite (hermes-agent 模式) + parentUuid 链 (Claude Code 模式):

┌─────────────────────────────────────────────────────────┐
│              mochagent 推荐会话存储架构                    │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  H2 嵌入式数据库 (~/.mocha/state.mv.db)                  │
│                                                          │
│  CREATE TABLE sessions (                                 │
│    id VARCHAR(36) PRIMARY KEY,        ← UUID             │
│    parent_session_id VARCHAR(36),     ← hermes 链        │
│    cwd VARCHAR(4096),                                    │
│    title VARCHAR(100),                                   │
│    model VARCHAR(100),                                   │
│    system_prompt CLOB,               ← prefix cache 恢复 │
│    started_at TIMESTAMP,                                 │
│    ended_at TIMESTAMP,                                   │
│    end_reason VARCHAR(50),                               │
│    message_count INT,                                    │
│    input_tokens BIGINT,                                  │
│    output_tokens BIGINT,                                 │
│    estimated_cost DOUBLE,                                │
│    archived BOOLEAN DEFAULT FALSE,   ← soft archive      │
│    source VARCHAR(20),               ← 'cli'|'web'|'sdk' │
│    ...                                                    │
│  );                                                      │
│                                                          │
│  CREATE TABLE messages (                                 │
│    id BIGINT AUTO_INCREMENT PRIMARY KEY,                  │
│    uuid VARCHAR(36) UNIQUE NOT NULL,  ← Claude Code UUID │
│    parent_uuid VARCHAR(36),          ← Claude Code 链    │
│    session_id VARCHAR(36) NOT NULL,                      │
│    role VARCHAR(20) NOT NULL,                            │
│    content CLOB,                                         │
│    tool_calls CLOB,                   ← JSON             │
│    tool_call_id VARCHAR(100),                            │
│    finish_reason VARCHAR(50),                            │
│    reasoning CLOB,                                       │
│    usage_json CLOB,                   ← JSON             │
│    timestamp TIMESTAMP NOT NULL,                         │
│    active BOOLEAN DEFAULT TRUE,      ← hermes soft-delete│
│    ...                                                    │
│  );                                                      │
│                                                          │
│  CREATE INDEX idx_messages_session_active                 │
│    ON messages(session_id, active, id);                   │
│  CREATE INDEX idx_messages_uuid ON messages(uuid);       │
│                                                          │
│  -- FTS: H2 支持 Apache Lucene 全文搜索                   │
│  CREATE ALIAS IF NOT EXISTS FTL_INIT FOR "...";          │
│                                                          │
└─────────────────────────────────────────────────────────┘

关键设计决策:
1. 用 H2/SQLite (hermes 模式) 替代纯 JSONL
   → 获得 FTS/结构化查询/事务支持

2. 保留 parentUuid 链 (Claude Code 模式)
   → 获得树状分支/删除+relink/compactBoundary 能力

3. 结合两者优势:
   • 查询: SQL (hermes 优势)
   • 链管理: parentUuid (Claude Code 优势)
   • 软删除: active=0 (hermes 优势)
   • Dedup: UUID unique constraint (Claude Code 优势)
   • 子代理: 独立 session 行 (hermes 模式) 或独立文件 (Claude Code 模式)
```

### 11.3 优先级实现路线

| 优先级 | 改进项 | 来源 |
|--------|--------|------|
| P0 | UUID unique constraint + dedup | Claude Code |
| P0 | parentUuid 链的 delete+relink | Claude Code |
| P0 | FTS 跨会话搜索 (Lucene) | hermes-agent |
| P1 | compactBoundary (parentUuid=null + logicalParentUuid) | Claude Code |
| P1 | soft-delete (active=0) | hermes-agent |
| P1 | system_prompt 持久化到 sessions 行 | hermes-agent |
| P2 | 批量缓冲写入 (100ms window) | Claude Code |
| P2 | lite read (64KB tail 快速列表) | Claude Code |
| P2 | 子代理独立文件/行隔离 | 两者结合 |
| P3 | 压缩锁机制 | hermes-agent |
| P3 | 循环检测 | Claude Code |
| P3 | 声明式 schema 演进 (reconcile columns) | hermes-agent |
