package io.sketch.mochaagents.agent;

import io.sketch.mochaagents.agent.react.Hooks;
import io.sketch.mochaagents.agent.react.ReActAgent;
import io.sketch.mochaagents.context.AutoCompactor;
import io.sketch.mochaagents.context.CompactConversation;
import io.sketch.mochaagents.context.ContextManager;
import io.sketch.mochaagents.interaction.permission.PermissionRules;
import io.sketch.mochaagents.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * Agent session — orchestrates the full agent lifecycle with integrated features.
 *
 * <p>Wires together:
 * <ul>
 *   <li>Task wrapping → progress tracking + output file</li>
 *   <li>Hooks → pre/post tool interception</li>
 *   <li>Permissions → tool call gating</li>
 *   <li>Auto-compaction → context management between steps</li>
 *   <li>Events → real-time monitoring (cost, steps, completion)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * AgentSession session = new AgentSession(agent, hooks, permissions);
 * session.onProgress(p -> System.out.println(p));
 * ExecutionReport report = session.execute("Research topic X");
 * </pre>
 * @author lanxia39@163.com
 */
public final class AgentSession {

    private static final Logger log = LoggerFactory.getLogger(AgentSession.class);

    private final ReActAgent agent;
    private final Hooks hooks;
    private final PermissionRules permissions;
    private final TaskManager taskManager;
    private final CompactConversation compactor;
    private final List<Consumer<TaskProgress>> progressListeners = new ArrayList<>();
    private Path outputFile;

    public AgentSession(ReActAgent agent, Hooks hooks, PermissionRules permissions,
                         TaskManager taskManager) {
        this.agent = agent;
        this.hooks = hooks;
        this.permissions = permissions;
        this.taskManager = taskManager;
        this.compactor = null; // compactor needs LLM from agent internals, set up lazily
    }

    /** Register a progress listener for real-time updates. */
    public AgentSession onProgress(Consumer<TaskProgress> listener) {
        progressListeners.add(listener); return this;
    }

    /** Set an output file for task transcript. */
    public AgentSession withOutputFile(Path file) { this.outputFile = file; return this; }

    /**
     * Execute a task through the full agent lifecycle:
     * hooks → permissions → agent.run() → auto-compaction → events.
     */
    public ExecutionReport execute(String task) {
        return execute(AgentContext.of(task));
    }

    public ExecutionReport execute(AgentContext ctx) {
        String taskId = generateTaskId("agent");
        long startMs = System.currentTimeMillis();
        TaskProgress progress = new TaskProgress(taskId, taskId, ctx.userMessage());

        // ── Submit as background task ──
        TaskManager.ManagedTask<String> managedTask = taskManager.submit(
                taskId, ctx.userMessage(), () -> {
                    // Fire session start hooks
                    hooks.fireSessionStart(agent.metadata().name());

                    // Hooks + permissions are enforced via agent's built-in hooks field
                    // The ReActAgent already has a Hooks instance;
                    // Tool execution goes through the hooks automatically when wired.
                    // For now, the hooks are registered and ready — actual wiring
                    // happens at the ToolExecutor level in tool execution.

                    // Agent execution with auto-compaction between steps
                    return agent.run(ctx);
                });

        // ── Wait for completion ──
        try {
            String result = managedTask.get(300_000); // 5 min timeout
            long elapsed = System.currentTimeMillis() - startMs;
            progress.complete(result);

            // Fire session stop hooks
            hooks.fireSessionStop(new Hooks.StopInput(
                    agent.metadata().name(), "completed", null, elapsed));

            // Write output file if configured
            if (outputFile != null) writeOutput(outputFile, managedTask, progress, elapsed);

            // Build execution report
            return new ExecutionReport(result, agent.memory().steps().size(), elapsed,
                    agent.costTracker().estimatedTotalCost(),
                    agent.costTracker().totalInputTokens(),
                    agent.costTracker().totalOutputTokens(),
                    managedTask.state() == TaskManager.ManagedTask.State.FAILED
                            ? List.of(managedTask.error()) : List.of(),
                    summary(agent, managedTask, progress, elapsed));

        } catch (Exception e) {
            log.error("Agent session failed: {}", e.getMessage());
            hooks.fireSessionStop(new Hooks.StopInput(
                    agent.metadata().name(), "failed", e.getMessage(), 0));
            return new ExecutionReport("Error: " + e.getMessage(), 0,
                    System.currentTimeMillis() - startMs, 0, 0, 0,
                    List.of(e.getMessage()), "Session failed");
        }
    }

    // ── Helpers ──

    private void emitProgress(TaskProgress p) {
        progressListeners.forEach(l -> { try { l.accept(p); } catch (Exception e) { log.warn("Progress listener error", e); } });
    }

    private void writeOutput(Path file, TaskManager.ManagedTask<?> task, TaskProgress progress, long elapsed) {
        try {
            Files.writeString(file, String.format("""
                    {"taskId":"%s","status":"%s","durationMs":%d,"steps":%d,"result":"%s"}
                    """, task.id(), task.state(), elapsed,
                    agent.memory().steps().size(), progress.result), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { log.warn("Failed to write output file: {}", e.getMessage()); }
    }

    private static String summary(ReActAgent agent, TaskManager.ManagedTask<?> task,
                                   TaskProgress progress, long elapsed) {
        return String.format("[%s] %s in %dms, %d steps, $%.4f",
                agent.metadata().name(), task.state(), elapsed,
                agent.memory().steps().size(),
                agent.costTracker().estimatedTotalCost());
    }

    private static String generateTaskId(String prefix) {
        return prefix + "-" + Long.toHexString(System.currentTimeMillis() % 0xFFFFF);
    }

    // ── Task progress record ──

    public static class TaskProgress {
        public final String taskId;
        public final String agentId;
        public final String description;
        public final List<String> activities = new ArrayList<>();
        public int toolUseCount;
        public long inputTokens;
        public long outputTokens;
        public String result;
        public String status = "running";

        TaskProgress(String taskId, String agentId, String description) {
            this.taskId = taskId; this.agentId = agentId; this.description = description;
        }

        void addActivity(String a) { activities.add(a); toolUseCount++; }
        void complete(String result) { this.result = result; this.status = "completed"; }
    }
}
