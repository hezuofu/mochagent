// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.memory;

import io.sketch.mochaagents.agent.loop.step.*;

import java.util.*;

/**
 * Single external interface for ALL memory operations — hermes-agent pattern.
 *
 * <p><strong>Facade</strong> over composed internal components:
 * <ul>
 *   <li>{@link StepTracker} — step/trajectory tracking
 *   <li>{@link WorkingContext} — per-session working memory
 *   <li>{@link MemoryStore} — persistent storage
 *   <li>{@link MemoryPlugin} list — external memory backends
 *   <li>{@link GlobalMemory} — cross-session global facts
 * </ul>
 *
 * <p>Session management lives at the Agent level ({@code ReActAgent}),
 * following the hermes-agent pattern where {@code AIAgent} owns session
 * persistence directly rather than delegating to the memory system.
 *
 * <pre>{@code
 * MemoryManager mem = MemoryManager.create(new InMemoryMemoryStore())
 *     .withPlugin(myPlugin)
 *     .withGlobalMemory();
 * String ctx = mem.workingContext() + mem.globalContext() + mem.buildPluginPrompt();
 * }</pre>
 *
 * @author lanxia39@163.com
 */
public class MemoryManager {

    private final StepTracker stepTracker = new StepTracker();
    private final WorkingContext workingContext = new WorkingContext();

    // ── Step tracking (delegate to StepTracker) ──

    public void remember(MemoryStep step) { stepTracker.remember(step); }
    public List<MemoryStep> steps() { return stepTracker.steps(); }
    public int stepCount() { return stepTracker.stepCount(); }
    public void reset(String sp) { stepTracker.reset(sp); }
    public void replaceStep(int index, MemoryStep step) { stepTracker.replaceStep(index, step); }
    public int truncateToStep(int stepNumber) { return stepTracker.truncateToStep(stepNumber); }
    public List<String> turnSummaries() { return stepTracker.turnSummaries(); }
    public int maxStepNumber() { return stepTracker.maxStepNumber(); }
    public boolean hasFinalAnswer() { return stepTracker.hasFinalAnswer(); }
    public String systemPrompt() { return stepTracker.systemPrompt(); }
    public void setSystemPrompt(String sp) { stepTracker.setSystemPrompt(sp); }

    public void appendSystemPrompt(String p) { stepTracker.appendSystemPrompt(p); }
    public void appendTask(String t) { stepTracker.appendTask(t); }
    public void appendPlanning(String p, String o, int in, int out) { stepTracker.appendPlanning(p, o, in, out); }
    public void appendAction(ActionStep a) { stepTracker.appendAction(a); }
    public void appendFinalAnswer(Object o) { stepTracker.appendFinalAnswer(o); }

    // ── Persistence (delegate to MemoryStore) ──

    private final MemoryStore store;

    public void save(MemoryRecord entry) { store.store(entry); }
    public Optional<MemoryRecord> recall(String id) { return store.get(id); }
    public void forget(String id) { store.forget(id); }
    public List<MemoryRecord> search(String query) { return store.search(query); }
    public MemoryStore store() { return store; }

    // ── Plugins ──

    private final List<MemoryPlugin> plugins = new ArrayList<>();

    /** @return the internal StepTracker for direct access (e.g. session resume). */
    public StepTracker stepTracker() { return stepTracker; }

    public MemoryManager withPlugin(MemoryPlugin p) { plugins.add(p); return this; }

    public String buildPluginPrompt() {
        StringBuilder sb = new StringBuilder();
        for (MemoryPlugin p : plugins) {
            String s = p.buildSystemPrompt();
            if (s != null && !s.isEmpty()) {
                sb.append(s).append("\n");
            }
        }
        return sb.toString();
    }

    public String prefetch(String msg) {
        StringBuilder sb = new StringBuilder();
        for (MemoryPlugin p : plugins) {
            String s = p.prefetch(msg);
            if (s != null && !s.isEmpty()) {
                sb.append(s).append("\n");
            }
        }
        return sb.toString();
    }

    public void sync(String u, String a) { plugins.forEach(p -> p.sync(u, a)); }
    public void onTurnStart(int t, String m) { plugins.forEach(p -> p.onTurnStart(t, m)); }

    // ── Working memory (delegate to WorkingContext) ──

    public void checkpoint(String ki, String sop) { workingContext.checkpoint(ki, sop); }

    public String workingContext() { return workingContext.workingContext(); }

    // ── Global memory ──

    private GlobalMemory globalMemory;

    public MemoryManager withGlobalMemory() { this.globalMemory = new GlobalMemory(); return this; }
    public MemoryManager withGlobalMemory(java.nio.file.Path file) { this.globalMemory = new GlobalMemory(file); return this; }

    public void settle(String fact) {
        if (globalMemory == null) {
            withGlobalMemory();
        }
        globalMemory.append(fact);
    }

    public String globalContext() {
        if (globalMemory == null) {
            return "";
        }
        String c = globalMemory.read();
        return c.isEmpty() ? "" : "\n## Global Memory\n" + c;
    }

    // ── Snapshot (delegate to StepTracker) ──

    public List<MemoryRecord> snapshot() { return stepTracker.snapshot(); }

    // ── Factory ──

    private MemoryManager(MemoryStore store) { this.store = store != null ? store : new JsonlMemoryStore(); }

    /** Create with default persistent store (~/.mocha/memory/records.jsonl). */
    public static MemoryManager create() { return new MemoryManager(new JsonlMemoryStore()); }
    public static MemoryManager create(MemoryStore store) { return new MemoryManager(store); }
}
