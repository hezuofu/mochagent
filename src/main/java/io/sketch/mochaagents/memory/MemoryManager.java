// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.memory;

import io.sketch.mochaagents.agent.loop.step.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single external interface for ALL memory operations — hermes-agent pattern.
 *
 * <p>Composes: step tracking + persistence + plugins + working + global memory.
 *
 * <pre>{@code
 * MemoryManager mem = MemoryManager.create(new InMemoryMemoryStore())
 *     .withPlugin(myPlugin)
 *     .withGlobalMemory();
 * String ctx = mem.workingContext() + mem.globalContext() + mem.buildPluginPrompt();
 * }</pre>
  * @author lanxia39@163.com
 */
public class MemoryManager {

    private volatile String systemPrompt;
    private final List<MemoryStep> steps = new CopyOnWriteArrayList<>();

    public void remember(MemoryStep step) { steps.add(step); }
    public List<MemoryStep> steps() { return Collections.unmodifiableList(steps); }
    public int stepCount() { return steps.size(); }
    public void reset(String sp) { steps.clear(); this.systemPrompt = sp; }
    /** Replace a step at given index (for compaction operations). */
    public void replaceStep(int index, MemoryStep step) {
        if (index >= 0 && index < steps.size()) steps.set(index, step);
    }

    // ── Turn-level restore ──

    /** Truncate history to the given step number (inclusive). Removes all steps after it. */
    public int truncateToStep(int stepNumber) {
        int removed = 0;
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i) instanceof ActionStep as && as.stepNumber() > stepNumber) {
                steps.remove(i);
                removed++;
            } else if (steps.get(i) instanceof ContentStep cs && i > 0
                    && steps.get(i - 1) instanceof ActionStep a
                    && a.stepNumber() > stepNumber) {
                steps.remove(i);
                removed++;
            }
        }
        return removed;
    }

    /** Summarize turns for display. */
    public List<String> turnSummaries() {
        List<String> turns = new ArrayList<>();
        for (var step : steps) {
            if (step instanceof ActionStep as) {
                String action = as.action() != null ? as.action() : "";
                if (action.length() > 50) action = action.substring(0, 50) + "...";
                String summary = "Step " + as.stepNumber() + ": "
                        + (as.isFinalAnswer() ? "✓ " : "→ ") + action;
                if (as.hasError()) summary += " ⚡";
                turns.add(summary);
            }
        }
        return turns;
    }

    /** Find the max step number. */
    public int maxStepNumber() {
        int max = 0;
        for (var step : steps) {
            if (step instanceof ActionStep as && as.stepNumber() > max) max = as.stepNumber();
        }
        return max;
    }
    public void setSystemPrompt(String sp) { this.systemPrompt = sp; }
    public String systemPrompt() { return systemPrompt; }
    public boolean hasFinalAnswer() { return steps.stream().anyMatch(s -> s instanceof ContentStep cs && cs.isFinalAnswer()); }

    public void appendSystemPrompt(String p) { steps.add(ContentStep.systemPrompt(p)); }
    public void appendTask(String t) { steps.add(ContentStep.task(t)); }
    public void appendPlanning(String p, String o, int in, int out) { steps.add(new PlanningStep(p, o, in, out)); }
    public void appendAction(ActionStep a) { steps.add(a); }
    public void appendFinalAnswer(Object o) { steps.add(ContentStep.finalAnswer(o)); }

    // ── Persistence ──
    private final MemoryStore store;

    public void save(MemoryRecord entry) { store.store(entry); }
    public Optional<MemoryRecord> recall(String id) { return store.get(id); }
    public void forget(String id) { store.forget(id); }
    public List<MemoryRecord> search(String query) { return store.search(query); }

    // ── Plugins ──
    private final List<MemoryPlugin> plugins = new ArrayList<>();

    public MemoryManager withPlugin(MemoryPlugin p) { plugins.add(p); return this; }
    public String buildPluginPrompt() { StringBuilder sb = new StringBuilder(); for (MemoryPlugin p : plugins) { String s = p.buildSystemPrompt(); if (s != null && !s.isEmpty()) sb.append(s).append("\n"); } return sb.toString(); }
    public String prefetch(String msg) { StringBuilder sb = new StringBuilder(); for (MemoryPlugin p : plugins) { String s = p.prefetch(msg); if (s != null && !s.isEmpty()) sb.append(s).append("\n"); } return sb.toString(); }
    public void sync(String u, String a) { plugins.forEach(p -> p.sync(u, a)); }
    public void onTurnStart(int t, String m) { plugins.forEach(p -> p.onTurnStart(t, m)); }

    // ── Working memory ──
    private String keyInfo = "", relatedSop = "";

    public void checkpoint(String ki, String sop) {
        if (ki != null && !ki.isBlank())
        this.keyInfo = ki;
        if (sop != null && !sop.isBlank())
            this.relatedSop = sop;
    }
    public String workingContext() {
        if (keyInfo.isEmpty() && relatedSop.isEmpty()) return "";
        return "\n## Working Memory\n" + (keyInfo.isEmpty() ? "" : "<key_info>" + keyInfo + "</key_info>\n") + (relatedSop.isEmpty() ? "" : "Check " + relatedSop + " if unclear.\n"); }

    // ── Global memory ──
    private GlobalMemory globalMemory;

    public MemoryManager withGlobalMemory() { this.globalMemory = new GlobalMemory(); return this; }
    public MemoryManager withGlobalMemory(Path file) { this.globalMemory = new GlobalMemory(file); return this; }
    public void settle(String fact) { if (globalMemory == null) withGlobalMemory(); globalMemory.append(fact); }
    public String globalContext() { if (globalMemory == null) return ""; String c = globalMemory.read(); return c.isEmpty() ? "" : "\n## Global Memory\n" + c; }

    // ── Snapshot ──
    public List<MemoryRecord> snapshot() { List<MemoryRecord> e = new ArrayList<>(); for (MemoryStep s : steps) { if (s instanceof ActionStep act) e.add(MemoryEntry.builder().content("[Step " + act.stepNumber() + "] " + (act.observation() != null ? act.observation() : act.modelOutput())).type(MemoryRecord.TYPE_EPISODIC).importance(act.hasError() ? 0.2 : 0.6).build()); else if (s instanceof ContentStep cs && cs.isFinalAnswer()) e.add(MemoryEntry.builder().content("Task result: " + cs.payload()).type(MemoryRecord.TYPE_SEMANTIC).concepts(Set.of("final", "output")).build()); } return e; }

    // ── Factory ──
    private MemoryManager(MemoryStore store) { this.store = store != null ? store : new JsonlMemoryStore(); }
    /** Create with default persistent store (~/.mocha/memory/records.jsonl). */
    public static MemoryManager create() { return new MemoryManager(new JsonlMemoryStore()); }
    public static MemoryManager create(MemoryStore store) { return new MemoryManager(store); }
    // ── Session persistence ──

    private final SessionStore sessionStore = new SessionStore();
    private SessionStore.Session currentSession;

    public MemoryManager startSession(String cwd, String userId) {
        try { currentSession = sessionStore.start(UUID.randomUUID().toString(), cwd, userId); }
        catch (IOException e) { /* fall through */ }
        // Bind FileHistory to session directory for persistent undo
        if (currentSession != null) {
            io.sketch.mochaagents.tool.FileHistory.getInstance()
                    .withSession(currentSession.id(), sessionStore.projectDir(cwd));
        }
        return this;
    }

    /** Resume an existing session — loads transcript into memory steps. */
    public MemoryManager resumeSession(String sessionId, String cwd, String userId) {
        try {
            // Load session metadata and transcript
            Path dir = sessionStore.projectDir(cwd);
            Path metaFile = dir.resolve(sessionId + ".meta.json");
            if (!Files.exists(metaFile)) {
                // Session not found, start fresh
                return startSession(cwd, userId);
            }
            currentSession = loadSessionMeta(metaFile);
            // Inject transcript into memory steps for context
            sessionStore.injectTranscriptToMemory(currentSession, this);
            // Re-bind FileHistory for persistent undo
            io.sketch.mochaagents.tool.FileHistory.getInstance()
                    .withSession(currentSession.id(), sessionStore.projectDir(cwd));
        } catch (IOException e) { /* fall through — start fresh */ }
        return this;
    }

    private SessionStore.Session loadSessionMeta(Path metaFile) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = new com.fasterxml.jackson.databind.ObjectMapper().readValue(metaFile.toFile(), Map.class);
        String id = (String) m.get("id");
        String userId = (String) m.getOrDefault("userId", "unknown");
        String cwd = (String) m.getOrDefault("cwd", ".");
        Path transcript = metaFile.resolveSibling(id + ".jsonl");
        Instant started = Instant.parse((String) m.get("startedAt"));
        return new SessionStore.Session(id, userId, cwd, metaFile, transcript, started);
    }

    public void appendToSession(String role, String content) {
        if (currentSession != null) {
            sessionStore.append(currentSession, role, content);
            currentSession.incrementMessageCount();
        }
    }

    public List<SessionStore.SessionMeta> listSessions(String cwd) {
        try { return sessionStore.listSessions(cwd); }
        catch (IOException e) { return List.of(); }
    }

    public List<Map<String, Object>> loadSessionHistory(SessionStore.Session session) {
        try { return sessionStore.loadTranscript(session); }
        catch (IOException e) { return List.of(); }
    }

    public SessionStore.Session currentSession() { return currentSession; }
    public SessionStore sessionStore() { return sessionStore; }

    // ── Cross-session search ──

    /**
     * Search across all session transcripts for a keyword query.
     * Returns top matches with context snippets and session metadata.
     */
    public List<SessionSearchResult> searchSessions(String query, String cwd, int maxResults) {
        List<SessionSearchResult> results = new ArrayList<>();
        String lower = query.toLowerCase();
        try {
            for (var meta : sessionStore.listSessions(cwd)) {
                Path transcript = meta.metaFile().resolveSibling(meta.id() + ".jsonl");
                if (!Files.exists(transcript)) continue;

                List<String> matches = new ArrayList<>();
                for (String line : Files.readAllLines(transcript)) {
                    if (line.toLowerCase().contains(lower)) {
                        try {
                            @SuppressWarnings("unchecked")
                            var m = new com.fasterxml.jackson.databind.ObjectMapper().readValue(line, Map.class);
                            String content = (String) m.getOrDefault("content", "");
                            // Extract context snippet (±80 chars around match)
                            int idx = content.toLowerCase().indexOf(lower);
                            int start = Math.max(0, idx - 80);
                            int end = Math.min(content.length(), idx + lower.length() + 80);
                            String snippet = (start > 0 ? "..." : "") + content.substring(start, end) + (end < content.length() ? "..." : "");
                            matches.add(snippet);
                        } catch (Exception ignored) {}
                    }
                    if (matches.size() >= 5) break; // max 5 snippets per session
                }

                if (!matches.isEmpty()) {
                    String title = meta.title() != null ? meta.title() : "Session " + meta.id().substring(0, 8);
                    results.add(new SessionSearchResult(meta.id(), title, meta.startedAt(), matches));
                }
            }
        } catch (IOException e) { /* skip */ }

        results.sort((a, b) -> Integer.compare(b.matches().size(), a.matches().size()));
        if (results.size() > maxResults) results = results.subList(0, maxResults);
        return results;
    }

    public List<SessionSearchResult> searchSessions(String query, String cwd) {
        return searchSessions(query, cwd, 10);
    }

    public record SessionSearchResult(String sessionId, String title,
                                       java.time.Instant startedAt, List<String> matches) {}

    // ── Session metadata helpers ──

    /** Generate a short title for the current session using the model. */
    public String generateTitle(io.sketch.mochaagents.model.Model model) {
        if (currentSession == null) return null;
        // Extract first user message + first assistant response for context
        String firstUser = "", firstAssistant = "";
        for (var step : steps) {
            if (step instanceof ContentStep cs && cs.isTask() && firstUser.isEmpty())
                firstUser = cs.text();
            else if (step instanceof ActionStep as && firstAssistant.isEmpty())
                firstAssistant = as.modelOutput();
        }
        if (firstUser.isEmpty()) return null;

        String prompt = "Generate a SHORT title (max 6 words) for a conversation that starts with:\n"
                + "User: " + (firstUser.length() > 200 ? firstUser.substring(0, 200) + "..." : firstUser) + "\n"
                + (firstAssistant.isEmpty() ? "" : "Assistant: " + (firstAssistant.length() > 200 ? firstAssistant.substring(0, 200) + "..." : firstAssistant) + "\n")
                + "\nTitle:";
        try {
            var req = io.sketch.mochaagents.model.ModelRequest.builder()
                    .prompt(prompt).maxTokens(32).temperature(0.3).build();
            var resp = model.complete(req);
            String title = resp.content().trim().replaceAll("^[\"']|[\"']$", "");
            if (title.length() > 80) title = title.substring(0, 80);
            sessionStore.updateMeta(currentSession, title, null);
            return title;
        } catch (Exception e) { return null; }
    }

    /** Finalize session — persist metadata including token/cost stats. */
    public void endSession(long totalInputTokens, long totalOutputTokens, double estimatedCost) {
        if (currentSession == null) return;
        try {
            // Re-read existing meta, update with stats
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(currentSession.meta().toFile(), Map.class);
            meta.put("endedAt", java.time.Instant.now().toString());
            meta.put("messageCount", currentSession.messageCount());
            meta.put("inputTokens", totalInputTokens);
            meta.put("outputTokens", totalOutputTokens);
            meta.put("estimatedCost", estimatedCost);
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValue(currentSession.meta().toFile(), meta);
        } catch (IOException e) { /* best-effort */ }
    }
}
