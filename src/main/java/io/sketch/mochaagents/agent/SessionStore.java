package io.sketch.mochaagents.agent;

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

    /** Append a message to the session transcript. */
    public void append(Session session, String role, String content) {
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("timestamp", Instant.now().toString());
            entry.put("role", role);
            entry.put("content", content);
            entry.put("sessionId", session.id());
            Files.writeString(session.transcript(),
                    JSON.writeValueAsString(entry) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { log.warn("Failed to append transcript: {}", e.getMessage()); }
    }

    /** List all sessions for a project, ordered by most recent first. */
    public List<SessionMeta> listSessions(String cwd) throws IOException {
        Path dir = projectDir(cwd);
        if (!Files.exists(dir)) return List.of();

        List<SessionMeta> sessions = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.meta.json")) {
            for (Path p : stream) {
                try {
                    Session s = loadSession(p);
                    sessions.add(new SessionMeta(s.id(), s.userId(), s.startedAt(), p));
                } catch (Exception e) { log.warn("Skip corrupted session: {}", p); }
            }
        }
        sessions.sort((a, b) -> b.startedAt().compareTo(a.startedAt()));
        return sessions;
    }

    /** Load a session transcript. Returns messages in chronological order. */
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

    /** Update session metadata (title, tags). */
    public void updateMeta(Session session, String title, List<String> tags) throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", session.id());
        meta.put("userId", session.userId());
        meta.put("cwd", session.cwd());
        meta.put("startedAt", session.startedAt().toString());
        meta.put("lastActiveAt", Instant.now().toString());
        if (title != null) meta.put("title", title);
        if (tags != null && !tags.isEmpty()) meta.put("tags", tags);
        Files.writeString(session.meta(), JSON.writeValueAsString(meta));
    }

    private void saveMeta(Session s) throws IOException {
        updateMeta(s, null, null);
    }

    private Session loadSession(Path metaFile) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = JSON.readValue(metaFile.toFile(), Map.class);
        String id = (String) m.get("id");
        String userId = (String) m.getOrDefault("userId", "unknown");
        String cwd = (String) m.getOrDefault("cwd", ".");
        Path transcript = metaFile.resolveSibling(id + ".jsonl");
        Instant started = Instant.parse((String) m.get("startedAt"));
        return new Session(id, userId, cwd, metaFile, transcript, started);
    }

    // ============ Types ============

    public record Session(String id, String userId, String cwd, Path meta, Path transcript, Instant startedAt) {}

    public record SessionMeta(String id, String userId, Instant startedAt, Path metaFile) {}
}
