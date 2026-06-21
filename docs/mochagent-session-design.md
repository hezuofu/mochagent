# mochagent 会话存储统一设计

> 融合 Claude Code 的 parentUuid 链 + hermes-agent 的 SQLite 中心化

---

## 设计原则

```
Claude Code 优点 → 采用:
  ✅ parentUuid 树状链 (支持分支/删除/relink)
  ✅ compactBoundary (逻辑断点, 不创建新会话行)
  ✅ 子代理独立文件 (agent-{id}.jsonl)
  ✅ Project 批量写入队列 (100ms 缓冲)
  ✅ ensureToolResultPairing
  ✅ MicroCompact

hermes-agent 优点 → 采用:
  ✅ 中心化 DB (所有会话元数据 + FTS 搜索)
  ✅ sessions 表 (30+ 列计费/模型/统计)
  ✅ system_prompt 持久化 (prefix cache 恢复)
  ✅ active=0 软删除
  ✅ 压缩锁 (compression_locks)
  ✅ 声明式 schema 演进
```

---

## 架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│                  mochagent SessionStore v2                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  H2 Embedded DB (~/.mocha/state.mv.db)                          │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ sessions — 会话元数据 (model, tokens, cost, title, ...)  │   │
│  │ messages — 消息链 (uuid, parent_uuid, role, content)     │   │
│  │ messages_fts — Lucene FTS 全文搜索                       │   │
│  │ compression_locks — 压缩并发锁                            │   │
│  │ schema_version — 声明式演进                               │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                  │
│  JSONL Files (~/.mocha/projects/<slug>/)                        │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ <sessionId>.jsonl — 完整转录 (备份 + 跨版本兼容)          │   │
│  │ <sessionId>/subagents/agent-<id>.jsonl — 子代理转录       │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                  │
│  SessionStore (Facade)                                           │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ start / resume / append / list / search / compact / ...  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## H2 Schema

```sql
-- 会话元数据 (hermes-agent 模式)
CREATE TABLE sessions (
    id VARCHAR(36) PRIMARY KEY,              -- UUID
    source VARCHAR(20) NOT NULL DEFAULT 'cli', -- cli|web|sdk|telegram|...
    user_id VARCHAR(128),
    model VARCHAR(100),
    model_config JSON,                        -- 模型配置快照
    system_prompt CLOB,                       -- 完整系统提示词 (prefix cache恢复)
    parent_session_id VARCHAR(36),            -- 父会话 (压缩链/branch链)
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP,
    end_reason VARCHAR(50),                   -- compression|branched|completed|...
    message_count INT DEFAULT 0,
    tool_call_count INT DEFAULT 0,
    input_tokens BIGINT DEFAULT 0,
    output_tokens BIGINT DEFAULT 0,
    cache_read_tokens BIGINT DEFAULT 0,       -- Anthropic prompt cache
    cache_write_tokens BIGINT DEFAULT 0,
    reasoning_tokens BIGINT DEFAULT 0,
    cwd VARCHAR(4096),                        -- 工作目录
    billing_provider VARCHAR(50),
    billing_base_url VARCHAR(1024),
    billing_mode VARCHAR(50),
    estimated_cost_usd DOUBLE DEFAULT 0,
    actual_cost_usd DOUBLE,
    cost_status VARCHAR(20),                  -- estimated|actual|unavailable
    cost_source VARCHAR(50),
    title VARCHAR(100),
    api_call_count INT DEFAULT 0,
    rewind_count INT DEFAULT 0,
    archived BOOLEAN DEFAULT FALSE,           -- 软归档
    FOREIGN KEY (parent_session_id) REFERENCES sessions(id)
);

-- 消息链 (Claude Code parentUuid 模式 + hermes-agent active)
CREATE TABLE messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,     -- 内部排序键
    uuid VARCHAR(36) UNIQUE NOT NULL,         -- Claude Code: 全局唯一 UUID
    parent_uuid VARCHAR(36),                  -- Claude Code: 链指向前一条
    logical_parent_uuid VARCHAR(36),          -- Claude Code: 压缩边界后保留
    session_id VARCHAR(36) NOT NULL REFERENCES sessions(id),
    role VARCHAR(20) NOT NULL,                -- user|assistant|system|tool
    content CLOB,                             -- 文本或 JSON 多模态
    tool_call_id VARCHAR(100),                -- 关联 tool_use
    tool_calls CLOB,                          -- JSON: tool_use 块
    tool_name VARCHAR(100),                   -- tool role
    finish_reason VARCHAR(50),                -- stop|length|tool_calls
    reasoning CLOB,                           -- thinking 内容
    usage_json CLOB,                          -- {input_tokens, output_tokens, ...}
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active BOOLEAN DEFAULT TRUE,              -- hermes-agent: 软删除
    observed BOOLEAN DEFAULT FALSE,           -- 用户已读
    is_compact_boundary BOOLEAN DEFAULT FALSE, -- Claude Code: 压缩边界
    agent_id VARCHAR(36),                     -- 子代理 ID (NULL=主会话)
    FOREIGN KEY (agent_id) REFERENCES sessions(id)
);

-- 全文搜索 (H2 Lucene)
CREATE ALIAS IF NOT EXISTS FTL_INIT FOR "...";
CALL FTL_INIT();

-- 压缩锁
CREATE TABLE compression_locks (
    session_id VARCHAR(36) PRIMARY KEY,
    holder VARCHAR(256) NOT NULL,             -- pid:tid:agent:nonce
    acquired_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL
);

-- 索引
CREATE INDEX idx_sessions_parent ON sessions(parent_session_id);
CREATE INDEX idx_sessions_started ON sessions(started_at DESC);
CREATE INDEX idx_messages_session ON messages(session_id, id);
CREATE INDEX idx_messages_session_active ON messages(session_id, active, id);
CREATE INDEX idx_messages_uuid ON messages(uuid);
CREATE INDEX idx_compression_locks_expires ON compression_locks(expires_at);
```

---

## Message 类型体系

```java
// 统一消息类型 — 结合两者优势
public sealed interface Message
    permits Message.User, Message.Assistant, Message.System, Message.Attachment {

    UUID uuid();
    UUID parentUuid();          // Claude Code: 树状链
    UUID logicalParentUuid();   // Claude Code: compactBoundary 后的逻辑父节点
    String sessionId();
    Instant timestamp();
    boolean isCompactBoundary(); // Claude Code: 压缩边界标记

    // ── 子类型 ──

    record User(
        UUID uuid, UUID parentUuid, UUID logicalParentUuid,
        String sessionId, Instant timestamp, boolean isCompactBoundary,
        String content,                           // 文本或 JSON 多模态
        List<ToolResultBlock> toolResults,        // Claude Code: tool_result blocks
        String toolUseResult,                     // 文本摘要
        UUID sourceToolAssistantUUID              // 来源 assistant
    ) implements Message {}

    record Assistant(
        UUID uuid, UUID parentUuid, UUID logicalParentUuid,
        String sessionId, Instant timestamp, boolean isCompactBoundary,
        List<ContentBlock> content,               // text|tool_use|thinking
        String model,
        String stopReason,
        Usage usage
    ) implements Message {}

    record System(
        UUID uuid, UUID parentUuid, UUID logicalParentUuid,
        String sessionId, Instant timestamp, boolean isCompactBoundary,
        String content,
        SystemSubtype subtype                     // compact_boundary|microcompact|api_error|...
    ) implements Message {}

    record Attachment(
        UUID uuid, UUID parentUuid, UUID logicalParentUuid,
        String sessionId, Instant timestamp, boolean isCompactBoundary,
        String content,
        String path                               // 文件路径 (MEMORY.md, CLAUDE.md)
    ) implements Message {}

    enum SystemSubtype {
        COMPACT_BOUNDARY, MICROCOMPACT_BOUNDARY,
        API_ERROR, LOCAL_COMMAND, STOP_HOOK_SUMMARY,
        TURN_DURATION, MEMORY_SAVED, BRIDGE_STATUS
    }
}
```

---

## SessionStore API (重构后)

```java
public class SessionStore {

    // ── 会话生命周期 ──
    Session start(String sessionId, String cwd, String userId);
    Session resume(String sessionId, String cwd, String userId);
    void   end(String sessionId, String reason);

    // ── 消息 CRUD ──
    UUID   append(Session session, Message message);
    List<Message> getMessages(String sessionId, boolean includeInactive);
    void   replaceMessages(String sessionId, List<Message> messages);
    // Claude Code pattern: 消息级操作
    void   deleteMessages(String sessionId, Set<UUID> toDelete);       // 删除+relink
    List<Message> getMessagesAfterCompactBoundary(String sessionId);   // API 调用用

    // ── 搜索 (hermes-agent FTS) ──
    List<SessionMeta> listSessions(String cwd, SessionFilter filter);
    List<SessionSearchResult> searchSessions(String query, String cwd);

    // ── 元数据 ──
    String generateTitle(Session session, Model model);
    void   updateMeta(Session session, MetaUpdate update);

    // ── 压缩 ──
    boolean tryAcquireCompressionLock(String sessionId, String holder);
    void    releaseCompressionLock(String sessionId, String holder);

    // ── Lite Read (Claude Code) ──
    List<SessionMeta> listSessionsLite(String cwd);  // 只读 tail 64KB

    // ── 子代理 ──
    void   appendAgentMessage(AgentId agentId, Message msg);
    List<Message> getAgentMessages(AgentId agentId);
}
```

---

## 消息链操作 (Claude Code 模式)

```java
// parentUuid 树状链 — 4 个核心操作:

// 1. 插入 (幂等)
void insertMessageChain(List<Message> messages, UUID startingParentUuid) {
    UUID cursor = startingParentUuid;
    for (Message msg : messages) {
        if (msg instanceof ProgressMessage) continue;  // 不参与链
        if (alreadyRecorded(msg.uuid())) { cursor = msg.uuid(); continue; } // dedup
        UUID effectiveParent = msg.isCompactBoundary() ? null : cursor;
        UUID logicalParent  = msg.isCompactBoundary() ? cursor : null;
        persist(msg.withChain(effectiveParent, logicalParent));
        cursor = msg.uuid();
    }
}

// 2. 重建
List<Message> buildConversationChain(String sessionId) {
    Map<UUID, Message> byUuid = loadAll(sessionId);
    Message leaf = findLeaf(byUuid);  // 最后一个消息
    return walkToRoot(leaf, byUuid).reverse();
}

// 3. 删除 + 重连
void deleteAndRelink(String sessionId, Set<UUID> toDelete) {
    Map<UUID, UUID> deletedParents = captureParents(toDelete);
    executeDelete(toDelete);
    for (Message survivor : survivors) {
        if (toDelete.contains(survivor.parentUuid())) {
            UUID newParent = resolve(survivor.parentUuid(), deletedParents);
            updateParent(survivor.uuid(), newParent);
        }
    }
}

// 4. 压缩后消息 (API 调用用)
List<Message> getMessagesAfterCompactBoundary(String sessionId) {
    // 找到最后一个 compactBoundary → 只返回它之后的消息
    return query("WHERE id > lastCompactBoundaryId AND active = true");
}
```

---

## 与现有代码的映射

```
当前 mochagent → 重构后:
─────────────────────────────────────
SessionStore.java
  Session              → Session (不变, 加更多字段)
  TranscriptEntry      → 替换为 Message sealed hierarchy
  SessionMeta          → 不变, 加 model/tokens/cost 字段
  SessionSearchResult  → 不变
  buildConversationChain → 保留, 用 UUID 链
  loadTranscript       → getMessages()
  append               → append() with dedup
  injectTranscriptToMemory → resume() with StepTracker

新增:
  Message sealed hierarchy      (types/message/ 包)
  SessionStore (H2 + JSONL)    (替换纯 JSONL)
  CompactBoundary 支持          (消息级断点)
  deleteAndRelink               (UUID 链修复)
  compression_locks             (并发安全)
  FTS 搜索                      (Lucene)
  lite read                     (tail 64KB)
```

---

## 实现路线

| Phase | 内容 | 预估 |
|-------|------|------|
| **P0** | `Message` sealed hierarchy 定义 | 类型系统基础 |
| **P0** | `SessionStore` 重构为 H2 + parentUuid | 存储核心 |
| **P1** | `deleteAndRelink` + `compactBoundary` | 链操作 |
| **P1** | `MicroCompact` 集成到 resolveMessages | 已部分完成 |
| **P1** | FTS 搜索 (H2 Lucene) | 跨会话搜索 |
| **P2** | `compression_locks` | 并发安全 |
| **P2** | `lite read` (tail 64KB) | 快速会话列表 |
| **P2** | 子代理 transcript 隔离 | agent-{id}.jsonl |
| **P3** | 声明式 schema 演进 | 自动加列 |
| **P3** | Project 批量写入队列 | 100ms 缓冲 |
