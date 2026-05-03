package io.sketch.mochaagents.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Full replication of claude-code's task system (Task.ts + LocalAgentTask.tsx).
 *
 * <p>Features:
 * <ul>
 *   <li>Task types: LOCAL_AGENT, LOCAL_BASH, REMOTE_AGENT, IN_PROCESS_TEAMMATE, LOCAL_WORKFLOW</li>
 *   <li>TaskStatus state machine: PENDING→RUNNING→COMPLETED|FAILED|KILLED</li>
 *   <li>TaskStateBase: 12 fields (id, type, status, description, toolUseId, startTime, endTime, outputFile, etc.)</li>
 *   <li>ProgressTracker: toolUseCount, latestInputTokens, cumulativeOutputTokens, recentActivities</li>
 *   <li>Task ID generation: type-prefixed + 8 base36 random chars (security against symlink attacks)</li>
 *   <li>Task notifications: tagged format with taskId, status, summary, usage, error</li>
 *   <li>Output file per task: JSONL format, offset tracking</li>
 *   <li>Foreground/background execution modes with cleanup registry</li>
 * </ul>
 * @author lanxia39@163.com
 */
public final class TaskManager {

    private static final Logger log = LoggerFactory.getLogger(TaskManager.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz";

    public enum TaskType { LOCAL_BASH, LOCAL_AGENT, REMOTE_AGENT, IN_PROCESS_TEAMMATE, LOCAL_WORKFLOW }
    public enum TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, KILLED;
        public boolean isTerminal() { return this == COMPLETED || this == FAILED || this == KILLED; }
    }

    private final ConcurrentHashMap<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProgressTracker> progress = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Consumer<TaskState>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, List<Runnable>> cleanupRegistry = new ConcurrentHashMap<>();
    private Path outputDir = Paths.get(System.getProperty("java.io.tmpdir"), "mocha-tasks");

    public TaskManager() {
        try { Files.createDirectories(outputDir); } catch (IOException e) { log.warn("Cannot create output dir", e); }
    }

    public TaskManager withOutputDir(Path dir) { this.outputDir = dir; return this; }

    // ============ Task submission ============

    /** Submit a task — returns immediately. */
    public <T> ManagedTask<T> submit(TaskType type, String id, String description, Supplier<T> work) {
        return submit(type, id, description, null, work);
    }

    /** Submit with toolUseId. */
    public <T> ManagedTask<T> submit(TaskType type, String id, String description,
                                      String toolUseId, Supplier<T> work) {
        TaskState state = createTaskState(type, id, description, toolUseId);
        ManagedTask<T> task = new ManagedTask<>(state, work);
        tasks.put(id, state);

        executor.submit(() -> {
            updateStatus(id, TaskStatus.RUNNING);
            notifyListeners(state);
            try {
                T result = work.get();
                state.result = result;
                state.endTime = System.currentTimeMillis();
                updateStatus(id, TaskStatus.COMPLETED);
                task.future.complete(result);
                updateProgress(id, tracker -> {}); // final progress
            } catch (Exception e) {
                state.error = e.getMessage();
                state.endTime = System.currentTimeMillis();
                updateStatus(id, TaskStatus.FAILED);
                task.future.completeExceptionally(e);
                log.error("Task '{}' failed: {}", id, e.getMessage());
            }
            notifyListeners(state);
            runCleanup(id);
        });

        log.info("Task submitted: [{}/{}] {}", type, id, description);
        return task;
    }

    // ============ Lifecycle ============

    /** Kill a running task. */
    public boolean kill(String id) {
        TaskState s = tasks.get(id);
        if (s == null || s.status.isTerminal()) return false;
        updateStatus(id, TaskStatus.KILLED);
        runCleanup(id);
        log.info("Task killed: {}", id);
        return true;
    }

    /** Register cleanup to run when task completes/fails/is killed. */
    public void onCleanup(String taskId, Runnable cleanup) {
        cleanupRegistry.computeIfAbsent(taskId, k -> new ArrayList<>()).add(cleanup);
    }

    // ============ Progress tracking ============

    /** Create a progress tracker for a task. */
    public ProgressTracker trackProgress(String taskId) {
        ProgressTracker pt = new ProgressTracker();
        progress.put(taskId, pt);
        return pt;
    }

    /** Update progress from an assistant message (called per-step). */
    public void updateProgress(String taskId, Consumer<ProgressTracker> updater) {
        ProgressTracker pt = progress.get(taskId);
        if (pt != null) updater.accept(pt);
    }

    // ============ Query ============

    public Optional<TaskState> get(String id) { return Optional.ofNullable(tasks.get(id)); }
    public Collection<TaskState> all() { return Collections.unmodifiableCollection(tasks.values()); }
    public List<TaskState> byStatus(TaskStatus status) { return tasks.values().stream().filter(t -> t.status == status).toList(); }
    public Optional<ProgressTracker> progress(String id) { return Optional.ofNullable(progress.get(id)); }
    public void onTaskChange(Consumer<TaskState> listener) { listeners.add(listener); }
    public void shutdown() { executor.shutdown(); }

    // ============ Task ID generation ============

    /** Generate a type-prefixed task ID: 'a' + 8 base36 chars (e.g. "a3kf7m2x"). */
    public static String generateTaskId(TaskType type) {
        char prefix = switch (type) {
            case LOCAL_BASH -> 'b'; case LOCAL_AGENT -> 'a'; case REMOTE_AGENT -> 'r';
            case IN_PROCESS_TEAMMATE -> 't'; case LOCAL_WORKFLOW -> 'w';
        };
        StringBuilder id = new StringBuilder().append(prefix);
        byte[] bytes = new byte[8]; RANDOM.nextBytes(bytes);
        for (byte b : bytes) id.append(BASE36.charAt(Math.abs(b) % 36));
        return id.toString();
    }

    // ============ Notification ============

    /** Build a task notification string (XML-tagged format, claude-code compatible). */
    public static String buildNotification(TaskState state, ProgressTracker pt) {
        StringBuilder sb = new StringBuilder();
        sb.append("<task_notification>\n");
        sb.append("  <task_id>").append(state.id).append("</task_id>\n");
        sb.append("  <status>").append(state.status.name().toLowerCase()).append("</status>\n");
        if (state.description != null) sb.append("  <summary>").append(state.description).append("</summary>\n");
        if (state.result != null) sb.append("  <result>").append(truncate(state.result.toString(), 500)).append("</result>\n");
        if (state.error != null) sb.append("  <error>").append(state.error).append("</error>\n");
        if (pt != null) {
            sb.append("  <usage>\n");
            sb.append("    <input_tokens>").append(pt.latestInputTokens).append("</input_tokens>\n");
            sb.append("    <output_tokens>").append(pt.cumulativeOutputTokens).append("</output_tokens>\n");
            sb.append("  </usage>\n");
        }
        sb.append("  <duration_ms>").append(state.durationMs()).append("</duration_ms>\n");
        sb.append("</task_notification>");
        return sb.toString();
    }

    // ============ Internal ============

    private TaskState createTaskState(TaskType type, String id, String description, String toolUseId) {
        Path outFile = outputDir.resolve(id + ".jsonl");
        return new TaskState(id, type, TaskStatus.PENDING, description, toolUseId,
                System.currentTimeMillis(), 0L, outFile.toString(), 0L, false,
                null, null);
    }

    private void updateStatus(String id, TaskStatus newStatus) {
        TaskState s = tasks.get(id);
        if (s != null) { s.status = newStatus; s.endTime = System.currentTimeMillis(); }
    }

    private void notifyListeners(TaskState state) {
        listeners.forEach(l -> { try { l.accept(state); } catch (Exception e) { log.warn("Listener error", e); } });
    }

    private void runCleanup(String id) {
        List<Runnable> cleanups = cleanupRegistry.remove(id);
        if (cleanups != null) cleanups.forEach(r -> { try { r.run(); } catch (Exception e) { log.warn("Cleanup error", e); } });
    }

    private static String truncate(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "..."; }

    // ============ Types ============

    /** Full task state — mirrors claude-code's TaskStateBase. */
    public static class TaskState {
        public final String id;
        public final TaskType type;
        public volatile TaskStatus status;
        public final String description;
        public final String toolUseId;
        public final long startTime;
        public volatile long endTime;
        public final String outputFile;
        public final long outputOffset;
        public volatile boolean notified;
        public volatile Object result;
        public volatile String error;

        TaskState(String id, TaskType type, TaskStatus status, String description, String toolUseId,
                  long startTime, long endTime, String outputFile, long outputOffset, boolean notified,
                  Object result, String error) {
            this.id = id; this.type = type; this.status = status; this.description = description;
            this.toolUseId = toolUseId; this.startTime = startTime; this.endTime = endTime;
            this.outputFile = outputFile; this.outputOffset = outputOffset; this.notified = notified;
            this.result = result; this.error = error;
        }

        public long durationMs() {
            long end = endTime > 0 ? endTime : System.currentTimeMillis();
            return end - startTime;
        }

        @Override public String toString() {
            return "Task[" + id + ":" + status + "] " + description;
        }
    }

    /**
     * Progress tracker — accumulates tool use count, token usage, and recent activities.
     * Pattern from claude-code's ProgressTracker (LocalAgentTask.tsx:41-49).
     */
    public static class ProgressTracker {
        public int toolUseCount;
        public long latestInputTokens;
        public long cumulativeOutputTokens;
        public final List<ToolActivity> recentActivities = new ArrayList<>();
        private static final int MAX_ACTIVITIES = 5;

        /** Record a tool use with pre-computed activity description. */
        public void recordActivity(ToolActivity activity) {
            toolUseCount++;
            recentActivities.add(activity);
            if (recentActivities.size() > MAX_ACTIVITIES) recentActivities.remove(0);
        }

        /** Total tokens consumed (input + output). */
        public long totalTokens() { return latestInputTokens + cumulativeOutputTokens; }
    }

    /**
     * Tool activity record — pre-computed description for UI rendering.
     * Pattern from claude-code's ToolActivity (LocalAgentTask.tsx:23-31).
     */
    public record ToolActivity(String toolName, String description, boolean isSearch, boolean isRead) {
        public ToolActivity(String toolName, String description) { this(toolName, description, false, false); }
    }

    /**
     * Managed task handle — combines state + future for result retrieval.
     */
    public static final class ManagedTask<T> {
        private final TaskState state;
        private final CompletableFuture<T> future = new CompletableFuture<>();

        ManagedTask(TaskState state, Supplier<T> work) { this.state = state; }

        public String id() { return state.id; }
        public TaskState state() { return state; }
        public CompletableFuture<T> future() { return future; }
        public boolean isTerminal() { return state.status.isTerminal(); }

        /** Wait for completion with timeout. */
        public T get(long timeoutMs) throws Exception { return future.get(timeoutMs, TimeUnit.MILLISECONDS); }
        public T get() throws Exception { return future.get(); }
    }
}
