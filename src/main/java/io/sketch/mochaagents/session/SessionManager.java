// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sketch.mochaagents.agent.loop.step.ActionStep;
import io.sketch.mochaagents.memory.StepTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;

/**
 * SessionManager — unified external interface for ALL session operations.
 *
 * <p>Session = container (id + metadata + lifecycle state machine)
 * Conversation = messages (JSONL transcript with UUID chain)
 *
 * <p>Session lifecycle: {@code (create) → ACTIVE → ENDED → ARCHIVED}
 *
 * <p>Single entry point for:
 * <ul>
 *   <li>Session lifecycle — {@link #open}, {@link #close}, {@link #archive}</li>
 *   <li>Message read/write — {@link #append}, {@link #readMessages}</li>
 *   <li>Session listing/search — {@link #list}, {@link #search}</li>
 *   <li>Agent resume — {@link #injectToStepTracker}</li>
 * </ul>
 *
 * @author lanxia39@163.com
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path baseDir;

    public SessionManager() {
        this(Paths.get(System.getProperty("user.home"), ".mocha", "projects"));
    }

    public SessionManager(Path baseDir) {
        this.baseDir = baseDir;
    }

    // ═══════════════════════════════════════════════════════════════
    // 1. 会话生命周期
    // ═══════════════════════════════════════════════════════════════

    /** Open a session — new if resumeId is null/blank, resume otherwise. */
    public Session open(String cwd, String userId, String resumeSessionId) {
        try {
            if (resumeSessionId != null && !resumeSessionId.isBlank()) {
                return resume(resumeSessionId, cwd, userId);
            }
            return start(UUID.randomUUID().toString(), cwd, userId);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open session", e);
        }
    }

    /** Start a new session. */
    public Session start(String sessionId, String cwd, String userId) throws IOException {
        Path dir = projectDir(cwd);
        Files.createDirectories(dir);
        Path transcript = dir.resolve(sessionId + ".jsonl");
        Path meta = dir.resolve(sessionId + ".meta.json");

        Session s = new Session(sessionId, userId, cwd, meta, transcript, Instant.now(),
                SessionStatus.ACTIVE, null);
        saveMeta(s);
        log.info("Session started: {} ({})", sessionId, transcript);
        return s;
    }

    /** Resume an existing session. */
    public Session resume(String sessionId, String cwd, String userId) throws IOException {
        Path dir = projectDir(cwd);
        Path metaFile = dir.resolve(sessionId + ".meta.json");
        if (!Files.exists(metaFile)) {
            log.warn("Session {} not found, starting fresh", sessionId);
            return start(sessionId, cwd, userId);
        }
        Session s = loadSessionMeta(metaFile);
        // Reopen if previously ended/archived
        if (s.status == SessionStatus.ENDED || s.status == SessionStatus.ARCHIVED) {
            s.status = SessionStatus.ACTIVE;
            s.endedAt = null;
            s.endReason = null;
        }
        saveMeta(s);
        log.info("Session resumed: {} ({} msgs)", sessionId, s.messageCount);
        return s;
    }

    /** Close a session — mark ended with reason. */
    public void close(Session session, EndReason reason) {
        if (session == null) return;
        session.status = SessionStatus.ENDED;
        session.endedAt = Instant.now();
        session.endReason = reason;
        try { saveMeta(session); }
        catch (IOException e) { log.warn("Failed to save session close metadata: {}", e.getMessage()); }
        log.info("Session closed: {} reason={}", session.id, reason);
    }

    /** Archive — hide from default listing. */
    public void archive(Session session) {
        if (session == null) return;
        session.status = SessionStatus.ARCHIVED;
        try { saveMeta(session); } catch (IOException e) { /* best-effort */ }
    }

    /** Unarchive — restore to ENDED. */
    public void unarchive(Session session) {
        if (session == null) return;
        session.status = SessionStatus.ENDED;
        try { saveMeta(session); } catch (IOException e) { /* best-effort */ }
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. 对话 — 消息追加与读取
    // ═══════════════════════════════════════════════════════════════

    /** Append a message to the session transcript with UUID chain (Claude Code pattern). */
    public String append(Session session, String role, String content) {
        return append(session, role, content, null, null);
    }

    public String append(Session session, String role, String content, String toolCallId, String toolName) {
        String msgUuid = UUID.randomUUID().toString();
        String parentUuid = session.lastMsgUuid();
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("uuid", msgUuid);
            entry.put("parentUuid", parentUuid);
            entry.put("timestamp", Instant.now().toString());
            entry.put("role", role);
            entry.put("content", content);
            if (toolCallId != null) entry.put("toolCallId", toolCallId);
            if (toolName != null) entry.put("toolName", toolName);
            entry.put("sessionId", session.id());
            Files.writeString(session.transcript(),
                    JSON.writeValueAsString(entry) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            session.setLastMsgUuid(msgUuid);
            session.incrementMessageCount();
        } catch (IOException e) {
            log.warn("Failed to append transcript: {}", e.getMessage());
        }
        return msgUuid;
    }

    /** Read all messages for a session. */
    public List<TranscriptEntry> readMessages(Session session) throws IOException {
        return loadTranscriptEntries(session);
    }

    /** Inject a session transcript into a StepTracker for agent resume. */
    public void injectToStepTracker(Session session, StepTracker tracker) throws IOException {
        List<TranscriptEntry> chain = buildConversationChain(session);
        for (var entry : chain) {
            switch (entry.role()) {
                case "system" -> tracker.appendSystemPrompt(entry.content());
                case "user" -> tracker.appendTask(entry.content());
                case "assistant" -> tracker.appendAction(new ActionStep(
                        tracker.stepCount() + 1, "", entry.content(), "", "", null, 0, 0, false));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. 会话列表与搜索
    // ═══════════════════════════════════════════════════════════════

    /** List all sessions for a project, most recent first. */
    public List<SessionMeta> list(String cwd) throws IOException {
        Path dir = projectDir(cwd);
        if (!Files.exists(dir)) return List.of();

        List<SessionMeta> sessions = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir, "*.meta.json")) {
            for (Path p : stream) {
                try {
                    Session s = loadSessionMeta(p);
                    String title = null;
                    int msgCount = 0;
                    Instant lastActive = s.startedAt();
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = JSON.readValue(p.toFile(), Map.class);
                        title = (String) m.get("title");
                        if (m.get("messageCount") instanceof Number n) msgCount = n.intValue();
                        String la = (String) m.get("lastActiveAt");
                        if (la != null) lastActive = Instant.parse(la);
                    } catch (Exception ignored) {}
                    sessions.add(new SessionMeta(s.id(), s.userId(), s.startedAt(),
                            lastActive, p, msgCount, title, s.status(), s.endReason()));
                } catch (Exception e) { log.warn("Skip corrupted session: {}", p); }
            }
        }
        sessions.sort((a, b) -> b.startedAt().compareTo(a.startedAt()));
        return sessions;
    }

    /** Search across all session transcripts for a keyword. */
    public List<SessionSearchResult> search(String query, String cwd, int maxResults) {
        List<SessionSearchResult> results = new ArrayList<>();
        String lower = query.toLowerCase();
        try {
            for (var meta : list(cwd)) {
                Path transcript = meta.metaFile().resolveSibling(meta.id() + ".jsonl");
                if (!Files.exists(transcript)) continue;

                List<String> matches = new ArrayList<>();
                for (String line : Files.readAllLines(transcript)) {
                    if (line.toLowerCase().contains(lower)) {
                        try {
                            var m = JSON.readValue(line, Map.class);
                            String content = (String) m.getOrDefault("content", "");
                            int idx = content.toLowerCase().indexOf(lower);
                            int start = Math.max(0, idx - 80);
                            int end = Math.min(content.length(), idx + lower.length() + 80);
                            matches.add((start > 0 ? "..." : "")
                                    + content.substring(start, end)
                                    + (end < content.length() ? "..." : ""));
                        } catch (Exception ignored) {}
                    }
                    if (matches.size() >= 5) break;
                }
                if (!matches.isEmpty()) {
                    String title = meta.title() != null ? meta.title()
                            : "Session " + meta.id().substring(0, 8);
                    results.add(new SessionSearchResult(meta.id(), title, meta.startedAt(), matches));
                }
            }
        } catch (IOException e) { /* skip */ }
        results.sort((a, b) -> Integer.compare(b.matches().size(), a.matches().size()));
        if (results.size() > maxResults) results = results.subList(0, maxResults);
        return results;
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. 元数据更新
    // ═══════════════════════════════════════════════════════════════

    /** Persist session metadata to its .meta.json sidecar file. */
    public void saveMeta(Session s) throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", s.id());
        meta.put("userId", s.userId());
        meta.put("cwd", s.cwd());
        meta.put("startedAt", s.startedAt().toString());
        meta.put("lastActiveAt", Instant.now().toString());
        meta.put("messageCount", s.messageCount());
        meta.put("status", s.status().name());
        meta.put("inputTokens", s.inputTokens());
        meta.put("outputTokens", s.outputTokens());
        meta.put("estimatedCostUsd", s.estimatedCostUsd());
        if (s.title() != null) meta.put("title", s.title());
        if (s.model() != null) meta.put("model", s.model());
        if (s.systemPrompt() != null) meta.put("systemPrompt", s.systemPrompt());
        if (s.parentSessionId() != null) meta.put("parentSessionId", s.parentSessionId());
        if (s.endReason() != null) meta.put("endReason", s.endReason().name());
        if (s.endedAt() != null) meta.put("endedAt", s.endedAt().toString());
        Files.writeString(s.meta(), JSON.writeValueAsString(meta));
    }

    /** Update token/cost counters. */
    public void updateTokens(Session session, long inputTokens, long outputTokens, double costUsd) {
        session.inputTokens += inputTokens;
        session.outputTokens += outputTokens;
        session.estimatedCostUsd += costUsd;
        try { saveMeta(session); } catch (IOException e) { /* best-effort */ }
    }

    /** Generate a short session title using the model. */
    public String generateTitle(Session session, io.sketch.mochaagents.model.Model model,
                                 String firstUserMsg, String firstAssistantMsg) {
        if (session == null || model == null || firstUserMsg == null || firstUserMsg.isEmpty()) return null;
        String prompt = "Generate a SHORT title (max 6 words) for a conversation that starts with:\n"
                + "User: " + (firstUserMsg.length() > 200 ? firstUserMsg.substring(0, 200) + "..." : firstUserMsg) + "\n"
                + (firstAssistantMsg != null && !firstAssistantMsg.isEmpty()
                        ? "Assistant: " + (firstAssistantMsg.length() > 200
                                ? firstAssistantMsg.substring(0, 200) + "..." : firstAssistantMsg) + "\n" : "")
                + "\nTitle:";
        try {
            var req = io.sketch.mochaagents.model.ModelRequest.builder()
                    .prompt(prompt).maxTokens(32).temperature(0.3).build();
            var resp = model.complete(req);
            String title = resp.content().trim().replaceAll("^[\"']|[\"']$", "");
            if (title.length() > 80) title = title.substring(0, 80);
            session.setTitle(title);
            saveMeta(session);
            return title;
        } catch (Exception e) { return null; }
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. 项目目录
    // ═══════════════════════════════════════════════════════════════

    public Path projectDir(String cwd) {
        String hash = Integer.toHexString(cwd.hashCode());
        return baseDir.resolve(hash);
    }

    // ═══════════════════════════════════════════════════════════════
    // Internal: transcript reading + chain
    // ═══════════════════════════════════════════════════════════════

    public List<TranscriptEntry> buildConversationChain(Session session) throws IOException {
        List<TranscriptEntry> entries = loadTranscriptEntries(session);
        if (entries.isEmpty()) return List.of();

        Map<String, TranscriptEntry> byUuid = new LinkedHashMap<>();
        for (var e : entries) byUuid.put(e.uuid(), e);

        List<TranscriptEntry> chain = new ArrayList<>();
        String currentUuid = entries.get(entries.size() - 1).uuid();
        Set<String> seen = new HashSet<>(); // cycle guard
        while (currentUuid != null && seen.add(currentUuid)) {
            TranscriptEntry e = byUuid.get(currentUuid);
            if (e == null) break;
            chain.add(e);
            currentUuid = e.parentUuid();
        }
        Collections.reverse(chain);
        return chain;
    }

    public List<TranscriptEntry> loadTranscriptEntries(Session session) throws IOException {
        List<TranscriptEntry> entries = new ArrayList<>();
        if (!Files.exists(session.transcript())) return entries;

        for (String line : Files.readAllLines(session.transcript())) {
            if (line.isBlank()) continue;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = JSON.readValue(line, Map.class);
                String uuid = (String) m.getOrDefault("uuid", UUID.randomUUID().toString());
                String parentUuid = (String) m.get("parentUuid");
                String role = (String) m.getOrDefault("role", "system");
                String content = (String) m.getOrDefault("content", "");
                String timestamp = (String) m.getOrDefault("timestamp", Instant.now().toString());
                String sessionId = (String) m.get("sessionId");
                entries.add(new TranscriptEntry(uuid, parentUuid, role, content, timestamp, sessionId));
            } catch (Exception e) {
                log.warn("Skip corrupted transcript line: {}", e.getMessage());
            }
        }
        return entries;
    }

    private Session loadSessionMeta(Path metaFile) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = JSON.readValue(metaFile.toFile(), Map.class);
        String id = (String) m.get("id");
        String userId = (String) m.getOrDefault("userId", "unknown");
        String cwd = (String) m.getOrDefault("cwd", ".");
        Path transcript = metaFile.resolveSibling(id + ".jsonl");
        Instant started = Instant.parse((String) m.get("startedAt"));

        Session s = new Session(id, userId, cwd, metaFile, transcript, started,
                SessionStatus.ACTIVE, null);

        if (m.get("title") instanceof String t) s.title = t;
        if (m.get("messageCount") instanceof Number n) s.messageCount = n.intValue();
        if (m.get("inputTokens") instanceof Number n) s.inputTokens = n.longValue();
        if (m.get("outputTokens") instanceof Number n) s.outputTokens = n.longValue();
        if (m.get("estimatedCostUsd") instanceof Number n) s.estimatedCostUsd = n.doubleValue();
        if (m.get("model") instanceof String mo) s.model = mo;
        if (m.get("systemPrompt") instanceof String sp) s.systemPrompt = sp;
        if (m.get("parentSessionId") instanceof String ps) s.parentSessionId = ps;
        if (m.get("status") instanceof String st) s.status = SessionStatus.valueOf(st);
        if (m.get("endReason") instanceof String er) s.endReason = EndReason.valueOf(er);
        if (m.get("endedAt") instanceof String ea) s.endedAt = Instant.parse(ea);
        return s;
    }

    // ═══════════════════════════════════════════════════════════════
    // Types
    // ═══════════════════════════════════════════════════════════════

    /** Session entity — the container with lifecycle state machine. */
    public static class Session {
        private final String id, userId, cwd;
        private final Path meta, transcript;
        private final Instant startedAt;
        private volatile String lastMsgUuid;
        volatile int messageCount;
        volatile SessionStatus status;
        volatile Instant endedAt;
        volatile EndReason endReason;
        volatile String title, model, systemPrompt, parentSessionId;
        volatile long inputTokens, outputTokens;
        volatile double estimatedCostUsd;

        public Session(String id, String userId, String cwd, Path meta, Path transcript,
                Instant startedAt, SessionStatus status, EndReason endReason) {
            this.id = id; this.userId = userId; this.cwd = cwd;
            this.meta = meta; this.transcript = transcript; this.startedAt = startedAt;
            this.status = status; this.endReason = endReason;
        }

        public String id() { return id; }
        public String userId() { return userId; }
        public String cwd() { return cwd; }
        public Path meta() { return meta; }
        public Path transcript() { return transcript; }
        public Instant startedAt() { return startedAt; }
        public Instant endedAt() { return endedAt; }
        public SessionStatus status() { return status; }
        public EndReason endReason() { return endReason; }
        public String title() { return title; }
        public void setTitle(String t) { this.title = t; }
        public String model() { return model; }
        public void setModel(String m) { this.model = m; }
        public String systemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String sp) { this.systemPrompt = sp; }
        public String parentSessionId() { return parentSessionId; }
        public void setParentSessionId(String ps) { this.parentSessionId = ps; }
        public long inputTokens() { return inputTokens; }
        public long outputTokens() { return outputTokens; }
        public double estimatedCostUsd() { return estimatedCostUsd; }
        public String lastMsgUuid() { return lastMsgUuid; }
        public void setLastMsgUuid(String u) { this.lastMsgUuid = u; }
        public int messageCount() { return messageCount; }
        public void incrementMessageCount() { this.messageCount++; }
    }

    public record TranscriptEntry(String uuid, String parentUuid, String role,
                                   String content, String timestamp, String sessionId) {}

    public record SessionMeta(String id, String userId, Instant startedAt, Instant lastActiveAt,
                               Path metaFile, int messageCount, String title,
                               SessionStatus status, EndReason endReason) {
        public SessionMeta(String id, String userId, Instant startedAt, Path metaFile) {
            this(id, userId, startedAt, startedAt, metaFile, 0, null, null, null);
        }
    }

    public record SessionSearchResult(String sessionId, String title,
                                       Instant startedAt, List<String> matches) {}
}
