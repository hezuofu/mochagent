# mochagent 会话/对话系统 — 完整面向对象设计

## 领域模型 (Domain Model)

```
┌─────────────────────────────────────────────────────────────────────┐
│                         DOMAIN OBJECTS                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Project ──1:N──→ Session ──1:N──→ Message                           │
│  (cwd)           (容器)            (对话内容)                         │
│                   │                                                  │
│                   ├── status: ACTIVE | ENDED | ARCHIVED              │
│                   ├── parentSession → Session (压缩链)               │
│                   └── meta: title, model, tokens, cost               │
│                                                                      │
│  Message (sealed hierarchy)                                          │
│  ├── Message.User       (用户输入 | tool_result)                     │
│  ├── Message.Assistant  (模型响应 + tool_use + thinking)             │
│  ├── Message.System     (compact_boundary | api_error | ...)         │
│  └── Message.Attachment (记忆 | 上下文文件)                           │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

## 完整类图

```
┌──────────────────────────────────────────────────────────────────┐
│                                                                   │
│  <<interface>>               <<interface>>                        │
│  SessionLifecycle             MessageChain                        │
│  ────────────────             ────────────                        │
│  + start(cwd,user)            + parentUuid()                      │
│  + resume(id)                 + children()                        │
│  + end(id,reason)             + isCompactBoundary()               │
│  + archive(id)                                                  │
│                                                                   │
│       ▲                              ▲                            │
│       │                              │                            │
│       │                    ┌─────────┴──────────┐                │
│       │                    │                    │                │
│  SessionStore ──uses──→ SessionIndex       Transcript             │
│  (Facade)               (H2 metadata+FTS)   (JSONL log)           │
│                                                                   │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │ SessionStore                                              │    │
│  │ ────────────                                              │    │
│  │ - index: SessionIndex    ← H2 (元数据 + FTS + 索引)       │    │
│  │ - transcript: Transcript ← JSONL (消息全文)               │    │
│  │                                                           │    │
│  │ 核心:                                                      │    │
│  │ + start(Project, UserId) → Session                        │    │
│  │ + resume(SessionId) → SessionWithMessages                 │    │
│  │ + append(SessionId, Message) → void                        │    │
│  │ + getMessages(SessionId) → MessageChain                    │    │
│  │ + delete(SessionId, Set<UUID>) → void                      │    │
│  │ + compact(SessionId, Summary) → Session                    │    │
│  │ + list(Project, Filter) → List<SessionMeta>               │    │
│  │ + search(Project, Query) → List<SearchResult>              │    │
│  └──────────────────────────────────────────────────────────┘    │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

## 代码实现

```java
// ═══════════════════════════════════════════════════════════════════
// 1. 值对象 (Value Objects)
// ═══════════════════════════════════════════════════════════════════

/** 项目标识 — cwd 的规范化表示, 会话归属地 */
public record Project(String path) {
    private static final int MAX_LEN = 200;

    /** 文件系统安全的目录名 */
    public String slug() {
        String sanitized = path.replaceAll("[^a-zA-Z0-9]", "-");
        if (sanitized.length() <= MAX_LEN) return sanitized;
        return sanitized.substring(0, MAX_LEN) + "-" + Math.abs(path.hashCode());
    }

    public Path sessionDir() {
        return Path.of(System.getProperty("user.home"), ".mocha", "projects", slug());
    }
}

/** 用户标识 */
public record UserId(String value) {}

/** 会话标识 */
public record SessionId(String value) {
    public static SessionId generate() { return new SessionId(UUID.randomUUID().toString()); }
    public String shortId() { return value.substring(0, 8); }
}

/** 会话状态 */
public enum SessionStatus { ACTIVE, ENDED, ARCHIVED }

/** 结束原因 */
public enum EndReason { COMPLETED, COMPACTED, BRANCHED, TIMEOUT, CANCELLED }

/** 消息状态 */
public enum MessageStatus { ACTIVE, DELETED }


// ═══════════════════════════════════════════════════════════════════
// 2. 实体 (Entities)
// ═══════════════════════════════════════════════════════════════════

/** 会话 — 聚合根 */
public class Session {
    private final SessionId id;
    private final Project project;
    private final UserId userId;
    private SessionStatus status;
    private SessionId parentSessionId;    // 压缩/分支链
    private String title;
    private String model;
    private String systemPrompt;          // 持久化, 用于 prefix cache 恢复
    private Instant startedAt;
    private Instant endedAt;
    private EndReason endReason;
    private int messageCount;
    private long inputTokens;
    private long outputTokens;
    private long cacheReadTokens;
    private long cacheWriteTokens;
    private double estimatedCostUsd;

    // 工厂
    public static Session create(Project project, UserId userId) {
        Session s = new Session();
        s.id = SessionId.generate();
        s.project = project;
        s.userId = userId;
        s.status = SessionStatus.ACTIVE;
        s.startedAt = Instant.now();
        return s;
    }

    // 生命周期转换
    public void end(EndReason reason) {
        if (status != SessionStatus.ACTIVE) throw new IllegalStateException(...);
        status = SessionStatus.ENDED;
        endedAt = Instant.now();
        endReason = reason;
    }

    public void reopen() {
        if (status != SessionStatus.ENDED) throw new IllegalStateException(...);
        status = SessionStatus.ACTIVE;
        endedAt = null;
        endReason = null;
    }

    public void archive()   { status = SessionStatus.ARCHIVED; }
    public void unarchive() { status = SessionStatus.ENDED; }

    // 计数器 (追加消息时更新)
    public void recordMessage()           { messageCount++; }
    public void recordTokens(long in, long out, long cacheR, long cacheW) {
        inputTokens += in; outputTokens += out;
        cacheReadTokens += cacheR; cacheWriteTokens += cacheW;
    }

    // getters...
    public SessionId id() { return id; }
    public Project project() { return project; }
    public SessionStatus status() { return status; }
    public SessionId parentSessionId() { return parentSessionId; }
    public void setParentSessionId(SessionId parent) { this.parentSessionId = parent; }
    public String systemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String sp) { this.systemPrompt = sp; }
    public void setTitle(String t) { this.title = t; }
    // ... 其余 getters
}


// ═══════════════════════════════════════════════════════════════════
// 3. 消息 — sealed hierarchy (不可变)
// ═══════════════════════════════════════════════════════════════════

/** 消息链接口 — 所有消息实现的共同行为 */
public interface Chainable {
    UUID uuid();
    UUID parentUuid();          // 指向前一条, null=根
    UUID logicalParentUuid();   // compactBoundary 后的逻辑链
    boolean isCompactBoundary();
}

/** 消息 — sealed, 不可变 */
public sealed interface Message extends Chainable
    permits Message.User, Message.Assistant, Message.System, Message.Attachment {

    UUID uuid();
    SessionId sessionId();
    Instant timestamp();
    MessageStatus status();
    String contentPreview();   // 前200字符, 用于列表/搜索预览

    // ── 变体 ──

    record User(
        UUID uuid,
        UUID parentUuid,
        UUID logicalParentUuid,
        boolean isCompactBoundary,
        SessionId sessionId,
        Instant timestamp,
        MessageStatus status,
        String content,                       // 文本内容
        List<ToolResult> toolResults,         // 当作为 tool_result 时
        String toolUseResult,                 // 文本摘要
        UUID sourceToolAssistantUUID          // 来源 assistant 消息
    ) implements Message {
        public String contentPreview() { return content.length() > 200 ? content.substring(0, 200) + "..." : content; }

        @Builder
        public record ToolResult(String toolUseId, String name, String content, boolean isError) {}
    }

    record Assistant(
        UUID uuid,
        UUID parentUuid,
        UUID logicalParentUuid,
        boolean isCompactBoundary,
        SessionId sessionId,
        Instant timestamp,
        MessageStatus status,
        List<ContentBlock> content,           // text | tool_use | thinking
        String model,
        String stopReason,
        Usage usage,
        String reasoning                      // thinking 内容
    ) implements Message {
        public String contentPreview() { /* 提取 text block */ }
    }

    record System(
        UUID uuid,
        UUID parentUuid,
        UUID logicalParentUuid,
        boolean isCompactBoundary,
        SessionId sessionId,
        Instant timestamp,
        MessageStatus status,
        String content,
        Subtype subtype                       // COMPACT_BOUNDARY | API_ERROR | ...
    ) implements Message {
        public enum Subtype { COMPACT_BOUNDARY, MICROCOMPACT_BOUNDARY, API_ERROR,
                              LOCAL_COMMAND, STOP_HOOK_SUMMARY, MEMORY_SAVED }
        public String contentPreview() { return content; }
    }

    record Attachment(
        UUID uuid,
        UUID parentUuid,
        UUID logicalParentUuid,
        boolean isCompactBoundary,
        SessionId sessionId,
        Instant timestamp,
        MessageStatus status,
        String content,
        String path                            // MEMORY.md | CLAUDE.md | ...
    ) implements Message {
        public String contentPreview() { return content; }
    }
}


// ═══════════════════════════════════════════════════════════════════
// 4. 消息链 (MessageChain) — 遍历/查询
// ═══════════════════════════════════════════════════════════════════

/** 消息链 — 不可变视图, 从 root → leaf 的有序消息列表 */
public class MessageChain {
    private final List<Message> messages;  // root-first

    private MessageChain(List<Message> messages) { this.messages = List.copyOf(messages); }

    /** 从 Map 构建 — leaf→root walk + reverse */
    public static MessageChain from(Map<UUID, Message> byUuid) {
        if (byUuid.isEmpty()) return new MessageChain(List.of());

        // 找 leaf: 没有任何消息的 parentUuid 指向它
        Set<UUID> referenced = new HashSet<>();
        for (var m : byUuid.values())
            if (m.parentUuid() != null) referenced.add(m.parentUuid());

        Message leaf = byUuid.values().stream()
            .filter(m -> !referenced.contains(m.uuid()))
            .findFirst().orElse(byUuid.values().iterator().next());

        // Walk + reverse
        List<Message> chain = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Message cur = leaf;
        while (cur != null) {
            if (!seen.add(cur.uuid())) break; // cycle guard
            chain.add(cur);
            cur = cur.parentUuid() != null ? byUuid.get(cur.parentUuid()) : null;
        }
        Collections.reverse(chain);
        return new MessageChain(chain);
    }

    // 查询
    public List<Message> all()                          { return messages; }
    public Optional<Message> last()                     { return messages.isEmpty() ? Optional.empty() : Optional.of(messages.get(messages.size()-1)); }
    public List<Message> afterCompactBoundary()         { /* 从最后一个 compactBoundary 之后截取 */ }
    public List<Message> active()                       { return messages.stream().filter(m -> m.status() == MessageStatus.ACTIVE).toList(); }

    // 操作 → 返回新链
    public MessageChain append(Message msg)             { var copy = new ArrayList<>(messages); copy.add(msg); return new MessageChain(copy); }
    public MessageChain delete(Set<UUID> uuids)         { /* 软删除 + relink */ }
}


// ═══════════════════════════════════════════════════════════════════
// 5. 持久化层 — 两层策略
// ═══════════════════════════════════════════════════════════════════

/** 转录日志 — JSONL 追加写入, 消息的权威数据源 */
class Transcript {
    private final Path file;

    /** 追加一行 → JSONL */
    void append(Message msg) {
        String line = serialize(msg) + "\n";
        Files.writeString(file, line, APPEND, CREATE);
    }

    /** 读取全部 — 重建消息链 */
    List<Message> readAll() {
        return Files.lines(file)
            .filter(l -> !l.isBlank())
            .map(this::deserialize)
            .toList();
    }

    /** 读取压缩边界后的消息 */
    List<Message> readAfterCompactBoundary() {
        // 从尾部向前扫描, 找到最后一个 COMPACT_BOUNDARY
        // 返回它之后的所有行
    }

    /** 原子替换全部 (压缩/删除后重写) */
    void replaceAll(List<Message> messages) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        for (var m : messages) Files.writeString(tmp, serialize(m) + "\n", APPEND, CREATE);
        Files.move(tmp, file, ATOMIC_MOVE, REPLACE_EXISTING);
    }

    private String serialize(Message m) { /* JSON */ }
    private Message deserialize(String line) { /* JSON parse */ }
}

/** 会话索引 — H2 数据库, 元数据 + FTS + 快速查询 */
class SessionIndex {
    private final DataSource ds;

    // ── 会话 ──
    void insertSession(Session s);
    void updateSession(Session s);
    Optional<Session> getSession(SessionId id);
    List<SessionMeta> listSessions(Project project, SessionFilter filter);
    List<SessionMeta> searchSessions(Project project, String query);

    // ── 消息索引 ──
    void insertMessage(Message msg);              // 异步, 仅索引列
    void deleteMessages(SessionId sid, Set<UUID> uuids);
    void updateMessageParent(UUID uuid, UUID newParent);

    // ── FTS ──
    List<UUID> searchMessages(SessionId sid, String query);  // Lucene

    // ── Schema ──
    void reconcile();  // 声明式: diff SCHEMA_SQL vs live, ADD missing columns
}


// ═══════════════════════════════════════════════════════════════════
// 6. SessionStore — Facade
// ═══════════════════════════════════════════════════════════════════

public class SessionStore {
    private final SessionIndex index;       // H2
    private final Map<SessionId, Transcript> openTranscripts = new ConcurrentHashMap<>();

    // ═══ 会话生命周期 ═══

    public Session start(Project project, UserId userId) {
        Session s = Session.create(project, userId);
        index.insertSession(s);
        transcript(s).append(Message.System.builder()
            .sessionId(s.id()).parentUuid(null)
            .timestamp(Instant.now()).status(MessageStatus.ACTIVE)
            .content("Session started").subtype(Subtype.SESSION_START).build());
        return s;
    }

    public Session resume(SessionId id) {
        Session s = index.getSession(id)
            .orElseThrow(() -> new NoSuchSessionException(id));
        s.reopen();
        index.updateSession(s);
        return s;
    }

    public void end(SessionId id, EndReason reason) {
        Session s = index.getSession(id).orElseThrow();
        s.end(reason);
        index.updateSession(s);
        openTranscripts.remove(id);  // 关闭 transcript
    }

    public void archive(SessionId id) {
        Session s = index.getSession(id).orElseThrow();
        s.archive();
        index.updateSession(s);
    }

    // ═══ 对话 ═══

    /** 追加消息 — JSONL 同步写, H2 异步索引 */
    public Message append(SessionId id, Message msg) {
        // 1. JSONL 权威写入 (同步, 必须成功)
        transcript(id).append(msg);

        // 2. H2 索引 (异步, 失败不影响核心)
        CompletableFuture.runAsync(() -> index.insertMessage(msg));

        // 3. 会话计数器 (异步)
        CompletableFuture.runAsync(() -> {
            Session s = index.getSession(id).orElse(null);
            if (s != null) { s.recordMessage(); index.updateSession(s); }
        });

        return msg;
    }

    /** 读取完整消息链 */
    public MessageChain getMessages(SessionId id) {
        List<Message> all = transcript(id).readAll();
        return MessageChain.from(toMap(all));
    }

    /** API 调用 — 只读取压缩边界后的消息 */
    public MessageChain getMessagesForAPI(SessionId id) {
        List<Message> all = transcript(id).readAfterCompactBoundary();
        return MessageChain.from(toMap(all));
    }

    // ═══ 删除 ═══

    /** 软删除 — 标记 DELETED, 修复 parentUuid 链 */
    public void delete(SessionId id, Set<UUID> uuids) {
        // 1. 标记 H2 索引
        index.deleteMessages(id, uuids);

        // 2. 读当前 JSONL, 替换为删除后版本
        List<Message> all = transcript(id).readAll();
        MessageChain chain = MessageChain.from(toMap(all)).delete(uuids);
        transcript(id).replaceAll(chain.all());
    }

    /** 物理删除 */
    public void purge(SessionId id, Set<UUID> uuids) {
        List<Message> all = transcript(id).readAll()
            .stream().filter(m -> !uuids.contains(m.uuid())).toList();
        transcript(id).replaceAll(all);
        index.deleteMessages(id, uuids);  // 物理删
    }

    // ═══ 压缩 ═══

    public Session compact(SessionId oldId, String summary, String newSystemPrompt) {
        Session old = index.getSession(oldId).orElseThrow();

        // 1. 创建 compactBoundary 消息
        List<Message> tail = transcript(oldId).readAfterCompactBoundary();
        Message boundary = Message.System.builder()
            .parentUuid(null)                           // 新根
            .logicalParentUuid(tail.get(0).uuid())      // 保留逻辑链
            .isCompactBoundary(true)
            .subtype(Subtype.COMPACT_BOUNDARY)
            .build();

        // 2. 结束旧会话
        old.end(EndReason.COMPACTED);
        index.updateSession(old);

        // 3. 创建新会话 (链: parentSessionId = oldId)
        Session newSession = Session.create(old.project(), old.userId());
        newSession.setParentSessionId(oldId);
        newSession.setSystemPrompt(newSystemPrompt);
        index.insertSession(newSession);

        // 4. 写入压缩后的消息到新 transcript
        Transcript newTranscript = new Transcript(newSession.id());
        newTranscript.append(boundary);
        newTranscript.append(Message.System.of(summary, Subtype.COMPACT_SUMMARY));
        for (var m : tail) newTranscript.append(m);

        return newSession;
    }

    // ═══ 搜索/列表 ═══

    public List<SessionMeta> list(Project project, SessionFilter filter) {
        return index.listSessions(project, filter);
    }

    public List<SessionSearchResult> search(Project project, String query) {
        return index.searchSessions(project, query);
    }

    // ═══ 内部 ═══

    private Transcript transcript(SessionId id) {
        return openTranscripts.computeIfAbsent(id,
            k -> new Transcript(/* ~/.mocha/projects/<slug>/<id>.jsonl */));
    }

    private Map<UUID, Message> toMap(List<Message> messages) {
        return messages.stream().collect(Collectors.toMap(Message::uuid, m -> m));
    }
}


// ═══════════════════════════════════════════════════════════════════
// 7. SessionManager — Agent 级别 (在 BaseAgent 中)
// ═══════════════════════════════════════════════════════════════════

public abstract class BaseAgent<I, O> {
    protected final SessionStore sessions;  // 持久化
    protected Session currentSession;       // 当前会话

    /** 启动新会话 */
    protected void startSession(String cwd, String userId) {
        currentSession = sessions.start(new Project(cwd), new UserId(userId));
    }

    /** 恢复已有会话 */
    protected void resumeSession(String sessionId) {
        currentSession = sessions.resume(new SessionId(sessionId));
        // 从 MessageChain 恢复到 StepTracker
        MessageChain chain = sessions.getMessages(currentSession.id());
        for (Message m : chain.all()) {
            if (m instanceof Message.User u && u.toolResults().isEmpty()) {
                stepTracker.appendTask(u.content());
            } else if (m instanceof Message.Assistant a) {
                stepTracker.appendAction(/* ActionStep */);
            }
        }
    }

    /** 每轮对话追加消息 */
    protected void onTurnComplete(String userMsg, String assistantMsg) {
        sessions.append(currentSession.id(),
            Message.User.of(userMsg, currentSession.id(), lastMessageUuid()));
        sessions.append(currentSession.id(),
            Message.Assistant.of(assistantMsg, currentSession.id(), lastMessageUuid()));
    }

    /** 结束会话 */
    protected void endSession(EndReason reason) {
        sessions.end(currentSession.id(), reason);
    }
}
```

## 设计模式运用

```
Facade       SessionStore    → SessionIndex + Transcript, 对外一个入口
Strategy     SessionIndex    → H2 (默认) | SQLite | PostgreSQL
Builder      Message.User.builder()...build()
Factory      Session.create(project, userId)
Composite    MessageChain    → 一组消息的有序视图, 操作返回新链
State        会话三态: ACTIVE → ENDED → ARCHIVED
             消息双态: ACTIVE → DELETED
Repository   SessionStore    → 聚合根 (Session + Messages) 的持久化
```

## 与现有代码的迁移路径

```
当前                            →  目标
─────────────────────────────────────────
SessionStore.Session            →  Session (扩展, 加状态机/lifecycle)
SessionStore.TranscriptEntry    →  Message sealed hierarchy
SessionStore.append()           →  Transcript.append() + SessionIndex.insertMessage()
SessionStore.listSessions()     →  SessionIndex.listSessions() (H2)
SessionStore.searchSessions()   →  SessionIndex.searchSessions() (Lucene)
SessionStore.buildChain()       →  MessageChain.from()
SessionStore.injectTranscript*  →  BaseAgent.resumeSession()
MemoryManager  的 session 字段   →  已在 BaseAgent 中 (上轮完成了)
```
