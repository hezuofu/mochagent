// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * File history tracker — snapshots file content before each modification.
 * Enables undo/rollback (Claude Code FileHistorySnapshot pattern).
 *
 * <pre>{@code
 * // Before modifying a file:
 * FileHistory.getInstance().record(filePath);
 *
 * // Undo last change:
 * FileHistory.getInstance().undo();
 *
 * // Undo specific file:
 * FileHistory.getInstance().undo("/path/to/file.java");
 * }</pre>
 *
 * @author lanxia39@163.com
 */
public class FileHistory {

    private static final FileHistory INSTANCE = new FileHistory();
    private static final int MAX_HISTORY = 50;
    private static volatile int currentStep;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Deque<Snapshot> history = new ConcurrentLinkedDeque<>();
    private volatile Path persistFile;

    public static FileHistory getInstance() { return INSTANCE; }

    /** Set current step (called by agent before tool execution). */
    public static void setCurrentStep(int step) { currentStep = step; }

    /** Bind to a session directory for persistence (~/.mocha/projects/{hash}/). */
    public FileHistory withSession(String sessionId, Path projectDir) {
        this.persistFile = projectDir.resolve(sessionId + ".files.jsonl");
        // Load existing snapshots from disk
        load();
        return this;
    }

    /** Record a file's current state before modification. Uses the agent's current step. */
    public void record(String filePath, String toolName) {
        record(filePath, toolName, currentStep);
    }

    /** Record with explicit step number for turn-level restore. */
    public void record(String filePath, String toolName, int stepNumber) {
        try {
            Path path = Path.of(filePath).toAbsolutePath().normalize();
            String oldContent = null;
            boolean existed = Files.exists(path);
            if (existed) {
                oldContent = Files.readString(path, StandardCharsets.UTF_8);
            }
            Snapshot snap = new Snapshot(path, oldContent, existed, toolName, Instant.now(), stepNumber);
            history.addLast(snap);
            while (history.size() > MAX_HISTORY) history.removeFirst();
            appendToDisk(snap);
        } catch (IOException e) { /* can't read — skip snapshot */ }
    }

    /** Undo the most recent file change. */
    public String undo() {
        Snapshot snap = history.pollLast();
        if (snap == null) return null;
        rewriteDisk();
        return restore(snap);
    }

    /** Undo the most recent change to a specific file. */
    public String undo(String filePath) {
        Path target = Path.of(filePath).toAbsolutePath().normalize();
        Snapshot found = null;
        for (var it = history.descendingIterator(); it.hasNext(); ) {
            Snapshot s = it.next();
            if (s.path.equals(target)) { found = s; break; }
        }
        if (found == null) return null;
        history.remove(found);
        rewriteDisk();
        return restore(found);
    }

    /** Undo all file changes that happened after a given step (for turn-level restore). */
    public List<String> undoSinceStep(int stepNumber) {
        List<String> restored = new ArrayList<>();
        var it = history.descendingIterator();
        while (it.hasNext()) {
            Snapshot s = it.next();
            if (s.stepNumber > stepNumber) {
                it.remove();
                String path = restore(s);
                if (path != null) restored.add(path);
            }
        }
        if (!restored.isEmpty()) rewriteDisk();
        return restored;
    }

    /** List all tracked changes (most recent first). */
    public List<Snapshot> list() {
        List<Snapshot> result = new ArrayList<>(history);
        Collections.reverse(result);
        return result;
    }

    /** List changes for a specific file. */
    public List<Snapshot> list(String filePath) {
        Path target = Path.of(filePath).toAbsolutePath().normalize();
        return history.stream()
                .filter(s -> s.path.equals(target))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    public int size() { return history.size(); }
    public void clear() { history.clear(); if (persistFile != null) rewriteDisk(); }

    // ── Disk persistence ──

    private void load() {
        if (persistFile == null || !Files.exists(persistFile)) return;
        try {
            String content = Files.readString(persistFile);
            if (content.isBlank()) return;
            List<Map<String, Object>> entries = JSON.readValue(content, new TypeReference<>() {});
            for (var e : entries) {
                try {
                    String pathStr = (String) e.get("path");
                    String oldContent = (String) e.getOrDefault("oldContent", null);
                    boolean existed = (boolean) e.getOrDefault("existed", true);
                    String tool = (String) e.getOrDefault("toolName", "");
                    int step = ((Number) e.getOrDefault("stepNumber", 0)).intValue();
                    history.addLast(new Snapshot(Path.of(pathStr), oldContent, existed, tool,
                            Instant.parse((String) e.get("timestamp")), step));
                } catch (Exception ex) { /* skip corrupted entry */ }
            }
        } catch (IOException e) { /* file doesn't exist yet */ }
    }

    private void appendToDisk(Snapshot snap) {
        if (persistFile == null) return;
        try {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("path", snap.path.toString());
            if (snap.oldContent != null) e.put("oldContent", snap.oldContent);
            e.put("existed", snap.existed);
            e.put("toolName", snap.toolName);
            e.put("timestamp", snap.timestamp.toString());
            e.put("stepNumber", snap.stepNumber);
            Files.writeString(persistFile, JSON.writeValueAsString(e) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) { /* best-effort */ }
    }

    private void rewriteDisk() {
        if (persistFile == null) return;
        try {
            List<Map<String, Object>> all = new ArrayList<>();
            for (var s : history) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("path", s.path.toString());
                if (s.oldContent != null) e.put("oldContent", s.oldContent);
                e.put("existed", s.existed);
                e.put("toolName", s.toolName);
                e.put("timestamp", s.timestamp.toString());
                e.put("stepNumber", s.stepNumber);
                all.add(e);
            }
            Files.writeString(persistFile, JSON.writeValueAsString(all),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ex) { /* best-effort */ }
    }

    private String restore(Snapshot snap) {
        try {
            if (snap.existed && snap.oldContent != null) {
                Files.writeString(snap.path, snap.oldContent, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } else if (!snap.existed) {
                // File was created by the tool — delete it to undo
                Files.deleteIfExists(snap.path);
            }
            return snap.path.toString();
        } catch (IOException e) {
            return null;
        }
    }

    /** A single file snapshot — captures state before modification. */
    public record Snapshot(Path path, String oldContent, boolean existed,
                           String toolName, Instant timestamp, int stepNumber) {
        public Snapshot(Path path, String oldContent, boolean existed,
                        String toolName, Instant timestamp) {
            this(path, oldContent, existed, toolName, timestamp, 0);
        }
        public String fileName() { return path.getFileName() != null ? path.getFileName().toString() : path.toString(); }
        public boolean isNewFile() { return !existed; }
    }
}
