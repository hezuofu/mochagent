// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Persistent session storage — JSONL transcript files per session.
 * Pattern from claude-code's sessionStorage.ts (Project class + JSONL format).
 *
 * <p>Directory structure:
 * <pre>
 *   {dataDir}/projects/{projectHash}/
 *     {sessionId}.jsonl          — main conversation transcript
 *     {sessionId}.meta.json      — metadata (title, tags, timestamps)
 * </pre>
 * @author lanxia39@163.com
 */
public final class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path baseDir;

    public SessionStore() { this(Paths.get(System.getProperty("user.home"), ".mocha", "projects")); }
    public SessionStore(Path baseDir) { this.baseDir = baseDir; }

    /** Get or create the project directory for a given working directory. */
    public Path projectDir(String cwd) {
        String hash = Integer.toHexString(cwd.hashCode());
        return baseDir.resolve(hash);
    }

    /** Start a new session — creates the directory and returns the session path. */
    public Session start(String sessionId, String cwd, String userId) throws IOException {
        Path dir = projectDir(cwd);
        Files.createDirectories(dir);
        Path transcript = dir.resolve(sessionId + ".jsonl");
        Path meta = dir.resolve(sessionId + ".meta.json");

        Session s = new Session(sessionId, userId, cwd, meta, transcript, Instant.now());
        saveMeta(s);
        log.info("Session started: {} at {}", sessionId, transcript);
        return s;
    }

    /** Append a message to the session transcript with UUID chain (Claude Code pattern). */
    public String append(Session session, String role, String content) {
        String msgUuid = UUID.randomUUID().toString();
        String parentUuid = session.lastMsgUuid();
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("uuid", msgUuid);
            entry.put("parentUuid", parentUuid != null ? parentUuid : null);
            entry.put("timestamp", Instant.now().toString());
            entry.put("role", role);
            entry.put("content", content);
            entry.put("sessionId", session.id());
            Files.writeString(session.transcript(),
                    JSON.writeValueAsString(entry) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            session.setLastMsgUuid(msgUuid);
        } catch (IOException e) { log.warn("Failed to append transcript: {}", e.getMessage()); }
        return msgUuid;
    }

    /** List all sessions for a project, ordered by most recent first. */
    public List<SessionMeta> listSessions(String cwd) throws IOException {
        Path dir = projectDir(cwd);
        if (!Files.exists(dir)) return List.of();

        List<SessionMeta> sessions = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.meta.json")) {
            for (Path p : stream) {
                try {
                    Session s = loadSessionFromMeta(p);
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
                            lastActive, p, msgCount, title));
                } catch (Exception e) { log.warn("Skip corrupted session: {}", p); }
            }
        }
        sessions.sort((a, b) -> b.startedAt().compareTo(a.startedAt()));
        return sessions;
    }

    /** Update session metadata (title, tags). */
    public void updateMeta(Session session, String title, List<String> tags) throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", session.id());
        meta.put("userId", session.userId());
        meta.put("cwd", session.cwd());
        meta.put("startedAt", session.startedAt().toString());
        meta.put("lastActiveAt", Instant.now().toString());
        meta.put("messageCount", session.messageCount());
        if (title != null) meta.put("title", title);
        if (tags != null && !tags.isEmpty()) meta.put("tags", tags);
        Files.writeString(session.meta(), JSON.writeValueAsString(meta));
    }

    private void saveMeta(Session s) throws IOException {
        updateMeta(s, null, null);
    }

    private Session loadSessionFromMeta(Path metaFile) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = JSON.readValue(metaFile.toFile(), Map.class);
        String id = (String) m.get("id");
        String userId = (String) m.getOrDefault("userId", "unknown");
        String cwd = (String) m.getOrDefault("cwd", ".");
        Path transcript = metaFile.resolveSibling(id + ".jsonl");
        Instant started = Instant.parse((String) m.get("startedAt"));
        return new Session(id, userId, cwd, metaFile, transcript, started);
    }

    // ── Conversation chain reconstruction (Claude Code pattern) ──

    /** Build conversation chain from JSONL transcript — leaf-to-root via parentUuid. */
    public List<TranscriptEntry> buildConversationChain(Session session) throws IOException {
        List<TranscriptEntry> entries = loadTranscriptEntries(session);
        if (entries.isEmpty()) return List.of();

        // Build UUID → entry map, find leaf (last entry with no child)
        Map<String, TranscriptEntry> byUuid = new LinkedHashMap<>();
        for (var e : entries) byUuid.put(e.uuid(), e);

        // Walk parentUuid chain from last entry (leaf) to root
        List<TranscriptEntry> chain = new ArrayList<>();
        String currentUuid = entries.get(entries.size() - 1).uuid();
        while (currentUuid != null) {
            TranscriptEntry e = byUuid.get(currentUuid);
            if (e == null) break;
            chain.add(e);
            currentUuid = e.parentUuid();
        }
        Collections.reverse(chain); // root-first order
        return chain;
    }

    /** Load all transcript entries as typed records. */
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
            } catch (Exception e) { log.warn("Skip corrupted transcript line: {}", e.getMessage()); }
        }
        return entries;
    }

    /** Injects a session transcript into a MemoryManager as MemorySteps for resume. */
    public void injectTranscriptToMemory(Session session, MemoryManager memory) throws IOException {
        List<TranscriptEntry> chain = buildConversationChain(session);
        for (var entry : chain) {
            switch (entry.role()) {
                case "system" -> memory.appendSystemPrompt(entry.content());
                case "user" -> memory.appendTask(entry.content());
                case "assistant" -> memory.appendAction(new io.sketch.mochaagents.agent.loop.step.ActionStep(
                        memory.stepCount() + 1, "", entry.content(), "", "", null, 0, 0, false));
            }
        }
    }

    /** A single entry in a session transcript. */
    public record TranscriptEntry(String uuid, String parentUuid, String role,
                                   String content, String timestamp, String sessionId) {}

    /** Load messages as flat maps (legacy format, for backward compat). */
    public List<Map<String, Object>> loadTranscript(Session session) throws IOException {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (!Files.exists(session.transcript())) return messages;

        for (String line : Files.readAllLines(session.transcript())) {
            if (line.isBlank()) continue;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> msg = JSON.readValue(line, Map.class);
                messages.add(msg);
            } catch (Exception e) { log.warn("Skip corrupted transcript line: {}", e.getMessage()); }
        }
        return messages;
    }

    // ============ Types ============

    public static class Session {
        private final String id, userId, cwd;
        private final Path meta, transcript;
        private final Instant startedAt;
        private volatile String lastMsgUuid;
        private volatile int messageCount;

        Session(String id, String userId, String cwd, Path meta, Path transcript, Instant startedAt) {
            this.id = id; this.userId = userId; this.cwd = cwd;
            this.meta = meta; this.transcript = transcript; this.startedAt = startedAt;
        }
        public String id() { return id; }
        public String userId() { return userId; }
        public String cwd() { return cwd; }
        public Path meta() { return meta; }
        public Path transcript() { return transcript; }
        public Instant startedAt() { return startedAt; }
        public String lastMsgUuid() { return lastMsgUuid; }
        public void setLastMsgUuid(String u) { this.lastMsgUuid = u; }
        public int messageCount() { return messageCount; }
        public void incrementMessageCount() { this.messageCount++; }
    }

    public record SessionMeta(String id, String userId, Instant startedAt, Instant lastActiveAt,
                              Path metaFile, int messageCount, String title) {
        public SessionMeta(String id, String userId, Instant startedAt, Path metaFile) {
            this(id, userId, startedAt, startedAt, metaFile, 0, null);
        }
    }
}
