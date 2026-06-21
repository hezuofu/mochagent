# mochagent 会话生命周期设计

## 1. 核心模型

```
Session (会话)                          Message (消息/对话内容)
─────────────                          ────────────────────────
id: "s1"                               uuid: "m1"
status: ACTIVE | ENDED | ARCHIVED      sessionId: "s1"           ← 外键
userId: "user1"                        parentUuid: null          ← 链
cwd: "/project"                        role: user | assistant | system | tool
title: "React App"                     content: ...
model: "sonnet"                        status: ACTIVE | DELETED  ← 单条消息状态
startedAt: 2026-06-07T10:00            deletedAt: null
endedAt: null                          ...
parentSessionId: null   ← 父会话(压缩链)

关系: Session 1 : N Message
      Session parentSessionId → Session id (压缩/branch链)
      Message parentUuid → Message uuid (对话链)
```

## 2. 会话状态机

```
              start()
  (不存在) ──────────→ ACTIVE
                         │
                         ├── append() ← 追加消息, 更新 messageCount/tokens
                         ├── setTitle()
                         ├── compact() → 创建子会话(parentSessionId=当前ID)
                         │               当前会话 end(COMPACTED)
                         │               新会话 ACTIVE
                         │
                         ├── end()
                         │     状态 → ENDED
                         │     记录 endedAt, endReason
                         │
                         ├── resume()
                         │     状态 → ACTIVE (如果已 ENDED)
                         │     重新打开 endedAt=null
                         │
                         └── archive()
                               状态 → ARCHIVED
                               不在默认列表显示, 可恢复

  ACTIVE ──end()──→ ENDED ──resume()──→ ACTIVE
    │                 │                    │
    │                 └──archive()──→ ARCHIVED ──unarchive()──→ ENDED
    │
    └──end(COMPACTED)──→ ENDED (有子会话)
                           │
                           └── 子会话 ACTIVE → ...
```

## 3. 消息状态机

```
每条消息有自己的生命周期:

  append() → ACTIVE
               │
               ├── delete() → DELETED
               │    (parentUuid 链自动修复)
               │
               └── compact() → 内容替换为占位符
                    (消息仍在, 状态不变, 只是 content 被清)

  ACTIVE ──delete()──→ DELETED
                         │
                         ├── restore() → ACTIVE (undo)
                         │
                         └── purge() → 物理删除 (不可恢复)
```

## 4. 完整 API

```java
public class SessionStore {

    // ═══════════════════════════════════════════════════════════
    // 会话生命周期
    // ═══════════════════════════════════════════════════════════

    /** 创建新会话 → ACTIVE */
    Session start(String cwd, String userId);

    /** 恢复已有会话 → ACTIVE (如果 ENDED/ARCHIVED → 重新激活) */
    Session resume(String sessionId);

    /** 结束会话 → ENDED */
    void end(String sessionId, EndReason reason);

    /** 归档 → ARCHIVED, 不在默认列表显示 */
    void archive(String sessionId);
    void unarchive(String sessionId);

    /** 列出会话 (默认排除 ARCHIVED, ENDED 包含) */
    List<SessionMeta> listSessions(String cwd, SessionFilter filter);

    /** 搜索所有会话的消息内容 */
    List<SessionSearchResult> search(String query, String cwd);

    // ═══════════════════════════════════════════════════════════
    // 对话 (消息) CRUD
    // ═══════════════════════════════════════════════════════════

    /** 追加一条消息 */
    Message append(String sessionId, Message msg);

    /** 读取完整对话链 (root→leaf, 排除 DELETED) */
    List<Message> getMessages(String sessionId);

    /** 仅读取压缩边界之后的消息 (API 调用) */
    List<Message> getMessagesAfterCompactBoundary(String sessionId);

    /** 软删除 — 标记 DELETED → 修复 parentUuid 链 */
    void deleteMessages(String sessionId, Set<UUID> messageUuids);

    /** 恢复被软删除的消息 → ACTIVE → 修复链 */
    void restoreMessages(String sessionId, Set<UUID> messageUuids);

    /** 物理删除 (不可恢复) */
    void purgeMessages(String sessionId, Set<UUID> messageUuids);

    /** 批量替换 (压缩用) */
    void replaceMessages(String sessionId, List<Message> newMessages);

    // ═══════════════════════════════════════════════════════════
    // 元数据
    // ═══════════════════════════════════════════════════════════

    void setTitle(String sessionId, String title);
    void updateTokens(String sessionId, long in, long out, long cacheRead,
                       long cacheWrite, double cost);
    void updateModel(String sessionId, String model);

    // ═══════════════════════════════════════════════════════════
    // 压缩
    // ═══════════════════════════════════════════════════════════

    /** 压缩当前会话: end(COMPACTED) → start(新会话, parent=当前) */
    Session compact(String sessionId, List<Message> compactedMessages,
                     String newSystemPrompt);

    boolean tryAcquireCompressionLock(String sessionId);
    void releaseCompressionLock(String sessionId);
}
```

## 5. 恢复 (Resume) 流程

```
resume(sessionId):
  │
  ├── 1. 查 sessions 表 → 获取 session 元数据
  │      SELECT * FROM sessions WHERE id = ?
  │
  ├── 2. 检查状态:
  │      ACTIVE   → 直接恢复 (可能另一个进程在用)
  │      ENDED    → reopen → ACTIVE → 恢复
  │      ARCHIVED → unarchive → ENDED → reopen → ACTIVE → 恢复
  │
  ├── 3. 读消息:
  │      JSONL: 顺序读所有行
  │      或 H2: SELECT * FROM messages WHERE session_id=? AND status='ACTIVE' ORDER BY id
  │
  ├── 4. 重建链:
  │      Map<UUID, Message> byUuid
  │      从最后一条消息沿 parentUuid walk 到根
  │      reverse → root-first
  │
  ├── 5. 恢复运行时状态:
  │      systemPrompt ← sessions.system_prompt (hermes-agent 模式)
  │      fileHistory  ← 从 session metadata 恢复快照
  │
  └── 6. 返回 Session + List<Message>
```

## 6. 删除流程 (parentUuid 链修复)

```
deleteMessages(sessionId, {m3, m4}):
  │
  │  删除前:  m1 → m2 → m3 → m4 → m5
  │
  ├── 1. 记录被删消息的 parentUuid:
  │      deletedParents = { m3: m2, m4: m3 }
  │
  ├── 2. 标记删除:
  │      UPDATE messages SET status='DELETED', deletedAt=now
  │      WHERE uuid IN (m3, m4)
  │
  ├── 3. 修复幸存者链:
  │      m5.parentUuid == m4? → m4 被删了
  │      → 上溯: m4.parentUuid = m3 → m3.parentUuid = m2
  │      → 新 parent: m2
  │      → UPDATE m5 SET parent_uuid = m2
  │
  └── 4. 结果:
  │      删除后:  m1 → m2 → m5
  │      (链连续, 无断点)
  │
  │  恢复 (restore) 是逆操作:
  │      m3, m4 → ACTIVE
  │      m5.parentUuid ← m4 (恢复原始链)
```

## 7. 压缩流程

```
compact(sessionId):

  压缩前:
    sessions: s1 { status: ACTIVE }
    messages: m1...m100

  步骤:
    1. tryAcquireCompressionLock(s1)  ← 防止并发压缩
    2. 对 m1...m80 做摘要 → summary
    3. 保留 m81...m100 (tail)
    4. 创建 compactBoundary 消息:
       Message.system()
         .parentUuid(null)           ← 新根!
         .logicalParentUuid(m80.uuid) ← 逻辑链保留
         .subtype(COMPACT_BOUNDARY)
    5. end(s1, COMPACTED)
    6. start(s2, parentSessionId=s1)

  压缩后:
    sessions:
      s1 { status: ENDED, endReason: COMPACTED }
      s2 { status: ACTIVE, parentSessionId: s1 }
    messages:
      s1: m1...m100 (保留, 可恢复)
      s2: [compactBoundary, summary, m81...m100]

  API 调用:
    getMessagesAfterCompactBoundary(s2) → [summary, m81...m100]
    getMessages(s1) → 完整历史 (如果用户想看)
```

## 8. 存储结构

```
~/.mocha/
├── state.mv.db                    ← H2: 所有结构化数据
│   ├── sessions                   ← 会话元数据 + 状态
│   ├── messages_index             ← 消息索引 (uuid, session_id, role, ts)
│   ├── messages_fts               ← Lucene 全文索引
│   └── compression_locks          ← 压缩锁
│
└── projects/
    └── <cwd-hash>/
        ├── <sessionId>.jsonl      ← 消息全文 (权威数据源)
        ├── <sessionId>.meta.json  ← 轻量元数据备份
        └── <sessionId>/
            └── subagents/
                └── agent-<id>.jsonl
```

```
H2 sessions 表:
  id, status, user_id, cwd, title, model,
  system_prompt, parent_session_id,
  started_at, ended_at, end_reason,
  message_count, input_tokens, output_tokens,
  cost_usd, archived

H2 messages_index 表:
  uuid, session_id, parent_uuid, role,
  timestamp, status, is_compact_boundary,
  content_preview (前200字符, 用于列表预览)

JSONL:
  每行 = 完整消息 JSON
  权威数据源 — H2 索引损坏可以从 JSONL 重建
```

## 9. 关键设计决策

```
写入路径:
  append(sessionId, msg)
    ├── 1. append JSONL 行           ← 同步，必须成功
    ├── 2. INSERT H2 messages_index   ← 异步，失败不阻塞
    └── 3. UPDATE sessions 计数       ← 异步

  为什么 JSONL 优先?
    - 追加即完成, 无事务开销
    - 人类可读, 可手动恢复
    - H2 损坏 → 从 JSONL 重建索引

读取路径:
  getMessages(sessionId)    → 读 JSONL (完整消息体)
  listSessions(cwd)         → 查 H2 (毫秒)
  searchSessions(query)     → H2 Lucene FTS

删除:
  - 默认软删除 (status=DELETED) → 可恢复
  - purge() 物理删除 → 重写 JSONL (移除对应行)
  - parentUuid 链自动修复

生命周期:
  - 三态状态机 (ACTIVE/ENDED/ARCHIVED)
  - 压缩创建新会话, parentSessionId 形成链
  - compactBoundary 消息标记逻辑断点
```
