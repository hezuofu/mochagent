package io.sketch.mochaagents.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Background task manager — lifecycl e management for concurrent agent tasks.
 * Pattern from claude-code's Task system (LocalAgentTask, remote_agent, etc.).
 *
 * <p>Usage:
 * <pre>
 * TaskManager tm = new TaskManager();
 * var task = tm.submit("research", "Research topic X", () -> agent.run("research X"));
 * // ... do other work ...
 * String result = task.get(60_000); // wait up to 60s
 * </pre>
 * @author lanxia39@163.com
 */
public final class TaskManager {

    private static final Logger log = LoggerFactory.getLogger(TaskManager.class);

    private final ConcurrentHashMap<String, ManagedTask<?>> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<java.util.function.Consumer<ManagedTask<?>>> listeners = new CopyOnWriteArrayList<>();

    /** Submit a background task. Returns immediately. */
    public <T> ManagedTask<T> submit(String id, String description, Supplier<T> work) {
        ManagedTask<T> task = new ManagedTask<>(id, description, work);
        tasks.put(id, (ManagedTask<?>) task);

        executor.submit(() -> {
            task.state = ManagedTask.State.RUNNING;
            task.startedAt = Instant.now();
            notifyListeners(task);
            try {
                T result = work.get();
                task.result = result;
                task.state = ManagedTask.State.COMPLETED;
                task.completedAt = Instant.now();
                task.future.complete(result);
            } catch (Exception e) {
                task.error = e.getMessage();
                task.state = ManagedTask.State.FAILED;
                task.completedAt = Instant.now();
                task.future.completeExceptionally(e);
                log.error("Task '{}' failed: {}", id, e.getMessage());
            }
            notifyListeners(task);
        });

        log.info("Task '{}' submitted: {}", id, description);
        return task;
    }

    /** Kill a running task. */
    public boolean kill(String id) {
        ManagedTask<?> task = tasks.get(id);
        if (task == null || task.isTerminal()) return false;
        task.state = ManagedTask.State.KILLED;
        task.future.cancel(true);
        log.info("Task '{}' killed", id);
        return true;
    }

    /** Get a task by ID. */
    public Optional<ManagedTask<?>> get(String id) { return Optional.ofNullable(tasks.get(id)); }

    /** List all tasks. */
    public Collection<ManagedTask<?>> all() { return Collections.unmodifiableCollection(tasks.values()); }

    /** List tasks by state. */
    public List<ManagedTask<?>> byState(ManagedTask.State state) {
        return tasks.values().stream().filter(t -> t.state == state).toList();
    }

    /** Register a listener for task state changes. */
    public void onTaskChange(java.util.function.Consumer<ManagedTask<?>> listener) { listeners.add(listener); }

    /** Shutdown the executor. */
    public void shutdown() { executor.shutdown(); }

    private void notifyListeners(ManagedTask<?> task) {
        listeners.forEach(l -> { try { l.accept(task); } catch (Exception e) { log.warn("Task listener error", e); } });
    }

    // ============ ManagedTask ============

    public static final class ManagedTask<T> {
        public enum State { PENDING, RUNNING, COMPLETED, FAILED, KILLED }

        private final String id;
        private final String description;
        private final Supplier<T> work;
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private volatile State state = State.PENDING;
        private volatile String error;
        private volatile T result;
        private volatile Instant startedAt;
        private volatile Instant completedAt;

        ManagedTask(String id, String description, Supplier<T> work) {
            this.id = id; this.description = description; this.work = work;
        }

        public String id() { return id; }
        public String description() { return description; }
        public State state() { return state; }
        public String error() { return error; }
        public T result() { return result; }
        public Optional<Instant> startedAt() { return Optional.ofNullable(startedAt); }
        public Optional<Instant> completedAt() { return Optional.ofNullable(completedAt); }
        public CompletableFuture<T> future() { return future; }
        public boolean isTerminal() { return state == State.COMPLETED || state == State.FAILED || state == State.KILLED; }
        public boolean isRunning() { return state == State.RUNNING; }

        /** Wait for completion with timeout. */
        public T get(long timeoutMs) throws Exception {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        }

        /** Block until completion. */
        public T get() throws Exception { return future.get(); }

        /** Duration in ms if completed. */
        public long durationMs() {
            if (startedAt == null || completedAt == null) return 0;
            return completedAt.toEpochMilli() - startedAt.toEpochMilli();
        }

        @Override public String toString() {
            return "Task[" + id + ":" + state + "] " + description;
        }
    }


}
