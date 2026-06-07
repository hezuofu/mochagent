# mochagent 会话模块 — 自闭环 + 统一对外接口

## 原则

```
外部只看到一个类: SessionStore
内部所有复杂度: Transcript, SessionIndex, MessageChain, lifecycle, 后台任务
外部不知道: JSONL 怎么写的, H2 怎么查的, 链怎么修的
```

## 统一接口

```java
public class SessionStore {

    // ═══════════════════════════════════════════════════════════
    // 外部只需这 8 个方法，其他全部是内部实现细节
    // ═══════════════════════════════════════════════════════════

    // ── 1. 会话生命周期 ──

    /** 新建 or 恢复 — 统一入口, 内部判断 */
    Session open(Project project, UserId userId, String resumeSessionId);

    /** 结束会话 */
    void close(Session session, EndReason reason);

    // ── 2. 对话读写 ──

    /** 追加消息 — 一条或多条, 内部处理去重/链/索引 */
    void append(Session session, Message... messages);

    /** 读取对话 — 返回完整链 or API 用链, 内部决定 */
    MessageChain read(Session session, ReadMode mode);

    // ── 3. 会话列表/搜索 ──

    /** 列出项目下的会话 */
    List<SessionMeta> list(Project project, SessionFilter filter);

    /** 全文搜索 */
    List<SearchResult> search(Project project, String query);

    // ── 4. 管理操作 ──

    /** 批量删除/恢复消息 */
    void modify(Session session, MessageModification mod);
}
```

## 内部闭环

```
┌─────────────────────────────────────────────────────────────────┐
│                      SessionStore (唯一外部接口)                  │
│                                                                  │
│  open()  close()  append()  read()  list()  search()  modify()  │
│                                                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────────────────┐ │
│  │ Transcript  │  │ SessionIndex │  │ MessageChain            │ │
│  │ (JSONL 消息) │  │ (H2 索引+FTS)│  │ (链遍历, 删除+relink)   │ │
│  │             │  │              │  │                         │ │
│  │ append()    │  │ insert()     │  │ from(messages)          │ │
│  │ readAll()   │  │ query()      │  │ delete(uuids) → new chain│
│  │ readTail()  │  │ search()     │  │ afterCompactBoundary()  │ │
│  │ replaceAll()│  │ reconcile()  │  │ append(msg) → new chain │ │
│  └──────┬──────┘  └──────┬───────┘  └────────────┬────────────┘ │
│         │                │                        │              │
│         │    ┌───────────┴───────────┐            │              │
│         │    │  Session (实体)        │            │              │
│         │    │  ACTIVE→ENDED→ARCHIVED│            │              │
│         │    └───────────────────────┘            │              │
│         │                                         │              │
│  ┌──────┴─────────────────────────────────────────┴──────┐      │
│  │                  内部协调逻辑                           │      │
│  │                                                       │      │
│  │  append(session, messages):                            │      │
│  │    1. Transcript.append()     ← 同步, 权威写入          │      │
│  │    2. CompletableFuture →     ← 异步, 失败不阻塞        │      │
│  │       SessionIndex.insert()                             │      │
│  │    3. session.recordMessage() ← 更新计数器               │      │
│  │                                                       │      │
│  │  read(session, FOR_API):                               │      │
│  │    MessageChain.from(                                  │      │
│  │      Transcript.readAfterCompactBoundary()             │      │
│  │    )                                                   │      │
│  │                                                       │      │
│  │  modify(session, DELETE of uuids):                     │      │
│  │    1. MessageChain.from(Transcript.readAll())          │      │
│  │         .delete(uuids)         ← 软删除 + relink       │      │
│  │    2. Transcript.replaceAll(newChain)                  │      │
│  │    3. SessionIndex.deleteMessages(uuids) ← 异步         │      │
│  │                                                       │      │
│  │  close(session, COMPLETED):                            │      │
│  │    1. session.end(COMPLETED)   ← 状态转换               │      │
│  │    2. SessionIndex.update()                              │      │
│  │    3. Transcript flush + close                           │      │
│  │                                                       │      │
│  └───────────────────────────────────────────────────────┘      │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

## 外部调用视角

```java
// BaseAgent 中 — 极简

// 新建会话
Session session = sessions.open(
    new Project(cwd), new UserId(userId), null);

// 恢复会话
Session session = sessions.open(
    new Project(cwd), new UserId(userId), "abc123");

// 每轮追加消息
sessions.append(session,
    Message.User.of(task, session.id(), lastUuid),
    Message.Assistant.of(response, session.id(), taskMsg.uuid()));

// 读取 (API 调用前)
MessageChain chain = sessions.read(session, ReadMode.FOR_API);

// 列出项目会话
List<SessionMeta> history = sessions.list(
    new Project(cwd), SessionFilter.DEFAULT);

// 搜索
List<SearchResult> results = sessions.search(
    new Project(cwd), "React component");

// 结束
sessions.close(session, EndReason.COMPLETED);
```

## 内部一致性保证

```
写入顺序保证幂等:
  append(session, msg):
    1. msg.uuid 已存在? → skip (已持久化过)
    2. Transcript.append() 成功 → 更新游标
    3. H2 写入失败 → 下次 read() 时从 JSONL 自动修复

JSONL 是权威:
  - 写入失败 → 抛出, 调用者重试
  - H2 损坏 → 从 JSONL 全量重建索引
  - 两者不同步 → JSONL 为准

后台任务:
  - H2 异步写入线程池 (2 threads, daemon)
  - shutdown() 时 drain 队列 + 关闭线程池
  - SessionIndex.reconcile() 在每次 open() 时自动检查/修复 schema

会话关闭:
  close() 时:
    1. 等待所有异步写入完成
    2. Flush Transcript buffer
    3. 从 openTranscripts 移除 (释放文件句柄)
    4. 更新 H2 结束状态
```

## 模块边界

```
session 模块导出:
  公开:  SessionStore, Session, Message, MessageChain, SessionMeta,
         Project, SessionId, UserId, ReadMode, SessionFilter, EndReason
  内部:  Transcript, SessionIndex, MessageSerializer, ...

外部依赖:
  H2 (嵌入式), Jackson (JSON), Lucene (FTS)
  不需要 Spring, 不需要 DI 容器

被谁使用:
  BaseAgent (持有 SessionStore)
  CLI (通过 MochaAgent → BaseAgent)
  测试 (直接创建 SessionStore)
```
