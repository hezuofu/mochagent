package io.sketch.mochaagents.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

/**
 * Per-user workspace — user identity, config, project isolation, session management.
 * Pattern from claude-code's ~/.claude directory structure.
 *
 * <pre>
 *   ~/.mocha/
 *     user.json              — persistent user ID + preferences
 *     settings.json           — global user settings
 *     projects/
 *       {projectHash}/
 *         settings.json       — project-specific settings
 *         sessions/
 *           {sessionId}.jsonl — conversation transcripts
 *   {cwd}/.mocha/
 *     settings.json           — local project settings (gitignored)
 * </pre>
 * @author lanxia39@163.com
 */
public final class UserWorkspace {

    private static final Logger log = LoggerFactory.getLogger(UserWorkspace.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path homeDir;
    private final String userId;
    private final SessionStore sessions;

    public UserWorkspace() { this(Paths.get(System.getProperty("user.home"), ".mocha")); }

    public UserWorkspace(Path homeDir) {
        this.homeDir = homeDir;
        this.userId = loadOrCreateUserId();
        this.sessions = new SessionStore(homeDir.resolve("projects"));
        try { Files.createDirectories(homeDir); } catch (IOException ignored) {}
    }

    // ============ Identity ============

    public String userId() { return userId; }

    private String loadOrCreateUserId() {
        Path userFile = homeDir.resolve("user.json");
        try {
            if (Files.exists(userFile)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = JSON.readValue(userFile.toFile(), Map.class);
                return (String) data.getOrDefault("userId", generateId());
            }
        } catch (IOException ignored) {}

        String id = generateId();
        try {
            Map<String, Object> data = Map.of("userId", id, "createdAt", java.time.Instant.now().toString());
            Files.createDirectories(homeDir);
            Files.writeString(userFile, JSON.writeValueAsString(data));
        } catch (IOException e) { log.warn("Cannot persist user ID", e); }
        return id;
    }

    private static String generateId() {
        byte[] bytes = new byte[16]; RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ============ Settings ============

    /** Load user-level settings. */
    public Map<String, Object> userSettings() { return loadJson(homeDir.resolve("settings.json")); }

    /** Load project-level settings. */
    public Map<String, Object> projectSettings(Path cwd) { return loadJson(cwd.resolve(".mocha").resolve("settings.json")); }

    /** Load local (gitignored) project settings. */
    public Map<String, Object> localSettings(Path cwd) { return loadJson(cwd.resolve(".mocha").resolve("settings.local.json")); }

    /** Load settings with hierarchy: user < project < local. */
    public Map<String, Object> mergedSettings(Path cwd) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.putAll(userSettings());
        merged.putAll(projectSettings(cwd));
        merged.putAll(localSettings(cwd));
        return merged;
    }

    // ============ Sessions ============

    public SessionStore sessions() { return sessions; }

    /** Start a new session in the user's workspace. */
    public SessionStore.Session startSession(String cwd) throws IOException {
        String sessionId = UUID.randomUUID().toString();
        return sessions.start(sessionId, cwd, userId);
    }

    /** List recent sessions for a project. */
    public List<SessionStore.SessionMeta> recentSessions(String cwd) throws IOException {
        return sessions.listSessions(cwd);
    }

    // ============ Helpers ============

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadJson(Path file) {
        try {
            if (Files.exists(file)) return JSON.readValue(file.toFile(), Map.class);
        } catch (IOException ignored) {}
        return Map.of();
    }

    public Path homeDir() { return homeDir; }
}
