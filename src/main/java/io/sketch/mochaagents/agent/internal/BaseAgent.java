// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.event.AgentEvent;
import io.sketch.mochaagents.agent.event.AgentListener;
import io.sketch.mochaagents.event.EventBus;
import io.sketch.mochaagents.agent.AgentMetadata;
import io.sketch.mochaagents.agent.AgentState;
import io.sketch.mochaagents.agent.loop.step.ActionStep;
import io.sketch.mochaagents.agent.loop.step.ContentStep;
import io.sketch.mochaagents.context.ContextChunk;
import io.sketch.mochaagents.context.Context;
import io.sketch.mochaagents.evaluation.EvaluationResult;
import io.sketch.mochaagents.evaluation.Evaluator;
import io.sketch.mochaagents.memory.MemoryRecord;
import io.sketch.mochaagents.memory.MemoryManager;
import io.sketch.mochaagents.memory.StepTracker;
import io.sketch.mochaagents.session.SessionManager;
import io.sketch.mochaagents.model.Model;
import io.sketch.mochaagents.model.ModelRequest;
import io.sketch.mochaagents.observability.Observability;
import io.sketch.mochaagents.reasoning.EffortLevel;
import io.sketch.mochaagents.reasoning.RecoveryStateMachine;
import io.sketch.mochaagents.reasoning.ThinkingConfig;
import io.sketch.mochaagents.safety.SafetyManager;
import io.sketch.mochaagents.tool.FileHistory;
import io.sketch.mochaagents.tool.Hooks;
import io.sketch.mochaagents.tool.ToolExecutor;
import io.sketch.mochaagents.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal agent base — name, state, tools, safety, and memory.
 *
 * <p>Cognitive capabilities (perception, reasoning, planning, evaluation)
 * live in concrete loop strategies or are assembled via {@link io.sketch.mochaagents.agent.Faculty}.
  * @author lanxia39@163.com
 */
public abstract class BaseAgent<I, O> implements Agent<I, O> {

    protected final String name;
    protected final String description;
    protected final List<AgentListener<I, O>> listeners = new CopyOnWriteArrayList<>();
    protected volatile AgentState state = AgentState.IDLE;

    protected final ToolRegistry toolRegistry;
    protected final SafetyManager safetyManager;
    protected final MemoryManager memoryManager;

    // ── Session persistence (hermes-agent pattern: Agent owns session, not MemoryManager) ──
    protected final io.sketch.mochaagents.session.SessionManager sessions;
    protected SessionManager.Session currentSession;

    protected final RecoveryStateMachine recovery;
    protected final Observability observability;
    protected final EventBus events = EventBus.async();
    protected final Hooks hooks = new Hooks();
    protected final ToolExecutor toolExecutor;
    protected ThinkingConfig thinkingConfig;
    protected EffortLevel effortLevel;

    protected BaseAgent(Builder<I, O, ?> builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.toolRegistry = builder.toolRegistry != null
                ? builder.toolRegistry : new ToolRegistry();
        this.safetyManager = builder.safetyManager;
        this.memoryManager = builder.memoryManager;
        this.sessions = builder.sessions != null
                ? builder.sessions : new io.sketch.mochaagents.session.SessionManager();
        this.recovery = new RecoveryStateMachine();
        this.observability = builder.observability != null
                ? builder.observability : io.sketch.mochaagents.observability.Observability.noop();
        this.thinkingConfig = builder.thinkingConfig != null
                ? builder.thinkingConfig : ThinkingConfig.adaptive();
        this.effortLevel = builder.effortLevel != null
                ? builder.effortLevel : EffortLevel.HIGH;
        this.toolExecutor = new ToolExecutor(this.toolRegistry, 60_000, 2, 500)
                .withHooks(hooks).withEvents(events);
    }

    protected abstract O doExecute(I input, AgentContext ctx);

    @Override
    public O execute(I input, AgentContext ctx) {
        state = AgentState.RUNNING;
        fireStart(input, ctx);
        try {
            O result = doExecute(input, ctx);
            state = AgentState.COMPLETED;
            fireComplete(result, ctx);
            return result;
        } catch (Exception e) {
            state = AgentState.FAILED;
            fireError(e, ctx);
            throw e;
        }
    }

    // ── Generic infrastructure (available to all agent types) ──

    public Hooks hooks() { return hooks; }
    public ToolExecutor toolExecutor() { return toolExecutor; }
    public EventBus events() { return events; }
    public MemoryManager memoryManager() { return memoryManager; }

    // ── Session management (hermes-agent pattern) ──

    public io.sketch.mochaagents.session.SessionManager sessions() { return sessions; }
    public SessionManager.Session currentSession() { return currentSession; }

    /** Start a new session — no-op if already active (resume in progress). */
    public void startSession(String cwd, String userId) {
        if (currentSession != null) return; // already resumed
        try {
            currentSession = sessions.start(java.util.UUID.randomUUID().toString(), cwd, userId);
            FileHistory.getInstance()
                    .withSession(currentSession.id(), sessions.projectDir(cwd));
        } catch (IOException e) { /* fall through */ }
    }

    /** Append a message to the current session transcript. */
    public void appendToSession(String role, String content) {
        if (currentSession != null) {
            sessions.append(currentSession, role, content);
            currentSession.incrementMessageCount();
        }
    }

    public List<SessionManager.SessionMeta> listSessions(String cwd) {
        try { return sessions.list(cwd); }
        catch (IOException e) { return java.util.List.of(); }
    }

    /** Resume an existing session — loads transcript into memory steps. */
    public void resumeSession(String sessionId, String cwd, String userId) {
        try {
            Path dir = sessions.projectDir(cwd);
            Path metaFile = dir.resolve(sessionId + ".meta.json");
            if (!Files.exists(metaFile)) {
                startSession(cwd, userId);
                return;
            }
            var meta = new ObjectMapper().readValue(metaFile.toFile(), java.util.Map.class);
            String id = (String) meta.get("id");
            String uid = (String) meta.getOrDefault("userId", "unknown");
            String cd = (String) meta.getOrDefault("cwd", ".");
            Path transcript = metaFile.resolveSibling(id + ".jsonl");
            Instant started = Instant.parse((String) meta.get("startedAt"));
            currentSession = new SessionManager.Session(id, uid, cd, metaFile, transcript, started,
                    io.sketch.mochaagents.session.SessionStatus.ACTIVE, null);

            sessions.injectToStepTracker(currentSession, memoryManager.stepTracker());
            // Restore system prompt from persisted session (for prefix cache continuity)
            if (currentSession.systemPrompt() != null) {
                memoryManager.stepTracker().setSystemPrompt(currentSession.systemPrompt());
            }
            FileHistory.getInstance()
                    .withSession(currentSession.id(), sessions.projectDir(cwd));
        } catch (IOException e) { /* fall through */ }
    }

    /** Finalize current session — persist metadata including token/cost stats. */
    public void endSession(long totalInputTokens, long totalOutputTokens, double estimatedCost) {
        if (currentSession == null) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = new ObjectMapper()
                    .readValue(currentSession.meta().toFile(), java.util.Map.class);
            meta.put("endedAt", Instant.now().toString());
            meta.put("messageCount", currentSession.messageCount());
            meta.put("inputTokens", totalInputTokens);
            meta.put("outputTokens", totalOutputTokens);
            meta.put("estimatedCost", estimatedCost);
            new ObjectMapper()
                    .writeValue(currentSession.meta().toFile(), meta);
        } catch (IOException e) { /* best-effort */ }
    }

    /** Search across all session transcripts for a keyword query. */
    public java.util.List<SessionManager.SessionSearchResult> searchSessions(
            String query, String cwd, int maxResults) {
        java.util.List<SessionManager.SessionSearchResult> results = new ArrayList<>();
        String lower = query.toLowerCase();
        try {
            for (var meta : sessions.list(cwd)) {
                Path transcript = meta.metaFile().resolveSibling(meta.id() + ".jsonl");
                if (!Files.exists(transcript)) {
                    continue;
                }

                java.util.List<String> matches = new ArrayList<>();
                for (String line : Files.readAllLines(transcript)) {
                    if (line.toLowerCase().contains(lower)) {
                        try {
                            var m = new ObjectMapper().readValue(line, java.util.Map.class);
                            String content = (String) m.getOrDefault("content", "");
                            int idx = content.toLowerCase().indexOf(lower);
                            int start = Math.max(0, idx - 80);
                            int end = Math.min(content.length(), idx + lower.length() + 80);
                            String snippet = (start > 0 ? "..." : "") + content.substring(start, end) + (end < content.length() ? "..." : "");
                            matches.add(snippet);
                        } catch (Exception ignored) {}
                    }
                    if (matches.size() >= 5) {
                        break;
                    }
                }
                if (!matches.isEmpty()) {
                    String title = meta.title() != null ? meta.title() : "Session " + meta.id().substring(0, 8);
                    results.add(new SessionManager.SessionSearchResult(meta.id(), title, meta.startedAt(), matches));
                }
            }
        } catch (IOException e) {
            /* skip */
        }
        results.sort((a, b) -> Integer.compare(b.matches().size(), a.matches().size()));
        if (results.size() > maxResults) {
            results = results.subList(0, maxResults);
        }
        return results;
    }

    /** Generate a short title for the current session using the model. */
    public String generateTitle(Model model) {
        if (currentSession == null || model == null) {
            return null;
        }
        // Extract first user message + first assistant response for context
        String firstUser = "", firstAssistant = "";
        for (var step : memoryManager.stepTracker().steps()) {
            if (step instanceof ContentStep cs && cs.isTask() && firstUser.isEmpty()) {
                firstUser = cs.text();
            } else if (step instanceof ActionStep as && firstAssistant.isEmpty()) {
                firstAssistant = as.modelOutput();
            }
        }
        if (firstUser.isEmpty()) {
            return null;
        }

        String prompt = "Generate a SHORT title (max 6 words) for a conversation that starts with:\n"
                + "User: " + (firstUser.length() > 200 ? firstUser.substring(0, 200) + "..." : firstUser) + "\n"
                + (firstAssistant.isEmpty() ? "" : "Assistant: " + (firstAssistant.length() > 200 ? firstAssistant.substring(0, 200) + "..." : firstAssistant) + "\n")
                + "\nTitle:";
        try {
            var req = ModelRequest.builder()
                    .prompt(prompt).maxTokens(32).temperature(0.3).build();
            var resp = model.complete(req);
            String title = resp.content().trim().replaceAll("^[\"']|[\"']$", "");
            if (title.length() > 80) {
                title = title.substring(0, 80);
            }
            currentSession.setTitle(title);
            sessions.saveMeta(currentSession);
            return title;
        } catch (Exception e) { return null; }
    }

    /** Build system prompt — subclasses override to provide agent-specific instructions. */
    public String buildSystemPrompt() { return ""; }

    // ── Metadata / listeners ──

    @Override
    public AgentMetadata metadata() {
        return new AgentMetadata(name, description);
    }

    @Override
    public void addListener(AgentListener<I, O> l) { listeners.add(l); }
    @Override
    public void removeListener(AgentListener<I, O> l) { listeners.remove(l); }

    protected void fireStart(I input, AgentContext ctx) {
        AgentEvent<I> e = new AgentEvent<>(name, input, ctx);
        for (AgentListener<I, O> l : listeners) {
            l.onStart(e);
        }
    }

    protected void fireComplete(O output, AgentContext ctx) {
        AgentEvent<O> e = new AgentEvent<>(name, output, ctx);
        for (AgentListener<I, O> l : listeners) {
            l.onComplete(e);
        }
    }

    protected void fireError(Throwable err, AgentContext ctx) {
        AgentEvent<Throwable> e = new AgentEvent<>(name, err, ctx);
        for (AgentListener<I, O> l : listeners) {
            l.onError(e);
        }
    }

    // ── Shared utilities (used by subclasses) ──

    protected EvaluationResult evaluate(String task, String result,
                                        Evaluator evaluator, Context ctx) {
        if (evaluator == null) return null;
        return evaluator.evaluate(task, result, null);
    }

    protected void injectMemories(String task, Context ctx) {
        if (memoryManager == null) {
            return;
        }
        for (MemoryRecord m : memoryManager.search(task != null ? task : "")) {
            ctx.addChunk(newChunk("memory", m.content()));
        }
    }

    protected static ContextChunk newChunk(String role, String content) {
        int tokens = content != null ? Math.max(1, content.length() / 4) : 1;
        return new ContextChunk(UUID.randomUUID().toString(), role, content, tokens);
    }

    // ── Tool calling (shared by all agents) ──

    protected static final Pattern ACTION_PATTERN =
            Pattern.compile("Action:\\s*(\\w+)\\((.*?)\\)", Pattern.DOTALL);
    protected static final Pattern JSON_ACTION_PATTERN =
            Pattern.compile("\"name\"\\s*:\\s*\"(\\w+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{[^}]+\\})");
    protected static final Pattern LOOSE_TOOL_PATTERN =
            Pattern.compile("(\\w+)\\s*\\(([^)]*)\\)");
    private static final Pattern KV_PAIR = Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern JSON_PAIR = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"");

    public record ParsedAction(String name, Map<String, Object> arguments) {
        public boolean isFinalAnswer() { return "final_answer".equals(name); }
    }

    /** Parse tool calls from typed Messages (native tool calling). */
    protected List<ParsedAction> parseContentBlocks(List<io.sketch.mochaagents.message.Message> messages) {
        List<ParsedAction> actions = new ArrayList<>();
        for (var msg : messages) {
            if (msg instanceof io.sketch.mochaagents.message.Message.AssistantMessage am) {
                for (var block : am.content()) {
                    if (block instanceof io.sketch.mochaagents.message.ContentBlock.ToolUseBlock t) {
                        actions.add(new ParsedAction(t.name(), t.input()));
                    }
                }
            }
        }
        return actions;
    }

    /** Parse tool call from Model output: JSON → "Action:" format → loose match. */
    protected ParsedAction parseAction(String modelOutput) {
        Matcher jm = JSON_ACTION_PATTERN.matcher(modelOutput);
        if (jm.find()) {
            return new ParsedAction(jm.group(1), parseJsonArgs(jm.group(2)));
        }

        Matcher am = ACTION_PATTERN.matcher(modelOutput);
        if (am.find()) {
            return new ParsedAction(am.group(1), parseKvArgs(am.group(2).trim()));
        }

        Matcher lm = LOOSE_TOOL_PATTERN.matcher(modelOutput);
        String lastName = null, lastArgs = null;
        while (lm.find()) { lastName = lm.group(1); lastArgs = lm.group(2); }
        if (lastName != null && toolRegistry != null && toolRegistry.has(lastName)) {
            return new ParsedAction(lastName, parseKvArgs(lastArgs != null ? lastArgs.trim() : ""));
        }

        return null;
    }

    protected Map<String, Object> parseKvArgs(String args) {
        Map<String, Object> result = new LinkedHashMap<>();
        Matcher m = KV_PAIR.matcher(args);
        while (m.find()) {
            result.put(m.group(1), m.group(2));
        }
        if (result.isEmpty() && !args.isEmpty()) {
            result.put("input", args.replace("\"", "").trim());
        }
        return result;
    }

    protected Map<String, Object> parseJsonArgs(String json) {
        Map<String, Object> result = new LinkedHashMap<>();
        Matcher m = JSON_PAIR.matcher(json);
        while (m.find()) {
            result.put(m.group(1), m.group(2));
        }
        return result;
    }

    protected <T> T withLlmRetry(java.util.function.Supplier<T> call, String step) {
        return withLlmRetry(call, step, 3);
    }

    /** Differentiated retry — hermes-agent pattern: vary backoff by error type. */
    protected <T> T withLlmRetry(java.util.function.Supplier<T> call, String step, int maxRetries) {
        RuntimeException last = null;
        for (int i = 0; i < maxRetries; i++) {
            try { return call.get(); }
            catch (io.sketch.mochaagents.MochaException.LlmException e) {
                last = e;
                int code = e.statusCode();
                if (code >= 400 && code < 500 && code != 429) {
                    // Client errors (4xx non-rate-limit) — don't retry, won't fix itself
                    throw e;
                }
                if (i < maxRetries - 1) {
                    long delay = code == 429 ? 5000L : (long) (1000 * Math.pow(2, i));
                    org.slf4j.LoggerFactory.getLogger(BaseAgent.class)
                            .warn("{} attempt {}/{} failed (HTTP {}): {}. Retrying in {}ms",
                                    step, i + 1, maxRetries, code, e.getMessage(), delay);
                    sleep(delay);
                }
            } catch (RuntimeException e) {
                last = e;
                if (i < maxRetries - 1) {
                    long delay = 1000L * (i + 1);
                    org.slf4j.LoggerFactory.getLogger(BaseAgent.class)
                            .warn("{} attempt {}/{} failed: {}. Retrying in {}ms",
                                    step, i + 1, maxRetries, e.getMessage(), delay);
                    sleep(delay);
                }
            }
        }
        throw last;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    // ── Builder ──

    @SuppressWarnings("unchecked")
    public abstract static class Builder<I, O, T extends Builder<I, O, T>> {
        protected String name = "base-agent";
        protected String description = "";

        protected ToolRegistry toolRegistry;
        protected SafetyManager safetyManager;
        protected MemoryManager memoryManager;
        protected io.sketch.mochaagents.session.SessionManager sessions;
        protected ThinkingConfig thinkingConfig;
        protected EffortLevel effortLevel;
        protected io.sketch.mochaagents.observability.Observability observability;

        public T name(String n) { this.name = n; return (T) this; }
        public T description(String d) { this.description = d; return (T) this; }
        public T toolRegistry(ToolRegistry r) { this.toolRegistry = r; return (T) this; }
        public T safetyManager(SafetyManager s) { this.safetyManager = s; return (T) this; }
        public T memoryManager(MemoryManager m) { this.memoryManager = m; return (T) this; }
        public T sessions(io.sketch.mochaagents.session.SessionManager s) { this.sessions = s; return (T) this; }
        public T thinkingConfig(ThinkingConfig c) { this.thinkingConfig = c; return (T) this; }
        public T effortLevel(EffortLevel e) { this.effortLevel = e; return (T) this; }
        public T observability(io.sketch.mochaagents.observability.Observability o) { this.observability = o; return (T) this; }

        public abstract BaseAgent<I, O> build();
    }
}
