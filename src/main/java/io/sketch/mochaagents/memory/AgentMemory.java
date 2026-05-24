package io.sketch.mochaagents.memory;

import io.sketch.mochaagents.agent.loop.step.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Unified agent memory — tracks execution steps AND persists long-term entries.
 *
 * <p>This is the single memory hub for all agents. Configure with a
 * {@link MemoryStore} for persistence, or use the no-arg constructor
 * for ephemeral (in-memory only) mode.
 *
 * <pre>{@code
 * Memory agentMemory = new AgentMemory(new InMemoryMemoryStore());
 * agentMemory.remember(new ActionStep(...));
 * agentMemory.save(MemoryEntry.of("key insight", Memory.TYPE_SEMANTIC));
 * }</pre>
 */
public class AgentMemory {

    private volatile String systemPrompt;
    private final List<MemoryStep> steps = new CopyOnWriteArrayList<>();
    private final MemoryStore store;

    public AgentMemory() { this(null); }

    public AgentMemory(MemoryStore store) {
        this.store = store != null ? store : new InMemoryMemoryStore();
    }

    // ── Step tracking ──

    public void setSystemPrompt(String prompt) { this.systemPrompt = prompt; }
    public String systemPrompt() { return systemPrompt; }

    /** Append a step to the execution history. */
    public void remember(MemoryStep step) { steps.add(step); }

    /** @deprecated use {@link #remember(MemoryStep)} */
    @Deprecated public void append(MemoryStep step) { remember(step); }

    public List<MemoryStep> steps() { return Collections.unmodifiableList(steps); }
    public void reset() { steps.clear(); }
    public void reset(String newSystemPrompt) { steps.clear(); this.systemPrompt = newSystemPrompt; }
    public int size() { return steps.size(); }

    public Optional<MemoryStep> lastStep() {
        return steps.isEmpty() ? Optional.empty() : Optional.of(steps.get(steps.size() - 1));
    }

    public boolean hasFinalAnswer() {
        return lastStep().filter(s -> s instanceof ContentStep cs && cs.isFinalAnswer()).isPresent();
    }

    // ── Convenience ──

    public void appendSystemPrompt(String prompt) { steps.add(ContentStep.systemPrompt(prompt)); }
    public void appendTask(String task) { steps.add(ContentStep.task(task)); }
    public void appendPlanning(String plan, String modelOutput, int inputTokens, int outputTokens) {
        steps.add(new PlanningStep(plan, modelOutput, inputTokens, outputTokens));
    }
    public void appendAction(ActionStep step) { steps.add(step); }
    public void appendFinalAnswer(Object output) { steps.add(ContentStep.finalAnswer(output)); }

    // ── Persistence (delegates to MemoryStore) ──

    /** Save a memory entry to persistent store. */
    public void save(Memory entry) { store.store(entry); }

    /** Recall a specific memory by id. */
    public Optional<MemoryRecord> recall(String id) { return store.get(id); }

    /** Forget a memory by id. */
    public void forget(String id) { store.forget(id); }

    /** Search persistent memories by query. */
    public List<MemoryRecord> search(String query) { return store.search(query); }

    /** All persistent entries. */
    public List<MemoryRecord> entries() { return store.entries().collect(Collectors.toList()); }

    /** Export execution trace as persistent Memory entries. */
    public List<MemoryRecord> snapshot() {
        List<MemoryRecord> entries = new ArrayList<>();
        for (MemoryStep s : steps) {
            if (s instanceof ActionStep act) {
                String content = "[Step " + act.stepNumber() + "] "
                        + (act.observation() != null ? act.observation() : act.modelOutput());
                entries.add(MemoryEntry.builder()
                        .content(content).type(Memory.TYPE_EPISODIC)
                        .importance(act.hasError() ? 0.2 : 0.6).build());
            } else if (s instanceof ContentStep cs && cs.isFinalAnswer()) {
                entries.add(MemoryEntry.builder()
                        .content("Task result: " + cs.payload()).type(Memory.TYPE_SEMANTIC)
                        .concepts(Set.of("final", "output")).build());
            }
        }
        return entries;
    }

    /** Restore historical entries into current execution trace. */
    public void restore(List<MemoryRecord> entries) {
        for (MemoryRecord e : entries) {
            ActionStep step = ActionStep.empty(steps.size() + 1);
            steps.add(new ActionStep(step.stepNumber(), "", "",
                    "restored", e.content(), null, 0, 0, false));
        }
    }

    /** Underlying store for direct access (advanced use). */
    public MemoryStore store() { return store; }

    // ── Working memory (GenericAgent: update_checkpoint) ──

    private String keyInfo = "";
    private String relatedSop = "";

    public void checkpoint(String ki, String sop) {
        if (ki != null && !ki.isBlank()) this.keyInfo = ki;
        if (sop != null && !sop.isBlank()) this.relatedSop = sop;
    }

    public String workingContext() {
        if (keyInfo.isEmpty() && relatedSop.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n## Working Memory\n");
        if (!keyInfo.isEmpty()) sb.append("<key_info>").append(keyInfo).append("</key_info>\n");
        if (!relatedSop.isEmpty()) sb.append("Check ").append(relatedSop).append(" if unclear.\n");
        return sb.toString();
    }

    // ── Global memory (GenericAgent: start_long_term_update) ──

    private GlobalMemory globalMemory;

    public void settle(String verifiedFact) {
        if (globalMemory == null)
            globalMemory = new GlobalMemory();
        globalMemory.append(verifiedFact);
    }

    public String globalContext() {
        if (globalMemory == null) return "";
        String content = globalMemory.read();
        return content.isEmpty() ? "" : "\n## Global Memory\n" + content;
    }

    // ── Composition ──

    public AgentMemory withPersistence(MemoryStore s) {
        return new AgentMemory(s);
    }

    public AgentMemory withGlobalMemory() {
        AgentMemory m = new AgentMemory(store);
        m.globalMemory = new GlobalMemory();
        return m;
    }

    public AgentMemory withGlobalMemory(java.nio.file.Path file) {
        AgentMemory m = new AgentMemory(store);
        m.globalMemory = new GlobalMemory(file);
        return m;
    }
}
