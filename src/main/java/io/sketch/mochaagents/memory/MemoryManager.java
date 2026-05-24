// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.memory;

import io.sketch.mochaagents.agent.loop.step.*;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Path;
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
 */
public class MemoryManager {

    private volatile String systemPrompt;
    private final List<MemoryStep> steps = new CopyOnWriteArrayList<>();

    public void remember(MemoryStep step) { steps.add(step); }
    @Deprecated public void append(MemoryStep step) { remember(step); }
    public List<MemoryStep> steps() { return Collections.unmodifiableList(steps); }
    public int stepCount() { return steps.size(); }
    @Deprecated public int size() { return stepCount(); }
    public void reset(String sp) { steps.clear(); this.systemPrompt = sp; }
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
    private MemoryManager(MemoryStore store) { this.store = store != null ? store : new InMemoryMemoryStore(); }
    public static MemoryManager create() { return new MemoryManager(new InMemoryMemoryStore()); }
    public static MemoryManager create(MemoryStore store) { return new MemoryManager(store); }
    // ── Session persistence ──

    private final SessionStore sessionStore = new SessionStore();
    private SessionStore.Session currentSession;

    public MemoryManager startSession(String cwd, String userId) {
        try { currentSession = sessionStore.start(UUID.randomUUID().toString(), cwd, userId); }
        catch (IOException e) { /* fall through */ }
        return this;
    }

    public void appendToSession(String role, String content) {
        if (currentSession != null) sessionStore.append(currentSession, role, content);
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
}
