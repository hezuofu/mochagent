package io.sketch.mochaagents.perception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

/**
 * Continuous perception loop — observes the environment after each action,
 * maintaining an up-to-date world model that feeds back into the agent's context.
 *
 * <p>Claude-code pattern: tool results feed back into system understanding via
 * per-cycle perception updates (tool result budget, context collapse, auto-compact).
 * This observer replicates that feedback loop.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var observer = new PerceptionObserver(new CodebasePerceptor(), new FileSystemPerceptor());
 * observer.observe("Task: refactor auth module");
 * // ... agent executes tool ...
 * observer.observeAction("Tool executed: grep found 15 files");
 * String enrichedContext = observer.buildEnrichedContext();
 * }</pre>
 *
 * @author lanxia39@163.com
 */
public class PerceptionObserver {

    private static final Logger log = LoggerFactory.getLogger(PerceptionObserver.class);

    private final List<Perceptor<?, ?>> perceptors;
    private final Deque<ObservationRecord> history = new ConcurrentLinkedDeque<>();
    private final int maxHistory;
    private final LayeredContextBuilder contextBuilder;

    // State tracking
    private int totalObservations;
    private long totalObservationTokens;
    private ZonedDateTime lastObservationTime;

    public PerceptionObserver(LayeredContextBuilder ctxBuilder, Perceptor<?, ?>... perceptors) {
        this(ctxBuilder, 50, perceptors);
    }

    public PerceptionObserver(LayeredContextBuilder ctxBuilder, int maxHistory,
                               Perceptor<?, ?>... perceptors) {
        this.contextBuilder = ctxBuilder;
        this.maxHistory = maxHistory;
        this.perceptors = List.of(perceptors);
    }

    /** Observe the initial task/environment — called once before the loop. */
    public PerceptionResult<String> observe(String input) {
        return observeInternal(input, "initial");
    }

    /** Observe after a tool action — called per-step. */
    public PerceptionResult<String> observeAction(String observation) {
        return observeInternal(observation, "action");
    }

    /** Async prefetch — start loading context while tool is executing (claude-code pattern). */
    public CompletableFuture<PerceptionResult<String>> prefetchAsync(String input) {
        return CompletableFuture.supplyAsync(() -> observeInternal(input, "prefetch"));
    }

    private PerceptionResult<String> observeInternal(String input, String phase) {
        long start = System.currentTimeMillis();
        StringBuilder combined = new StringBuilder();

        for (Perceptor<?, ?> p : perceptors) {
            try {
                @SuppressWarnings("unchecked")
                Perceptor<String, String> typed = (Perceptor<String, String>) p;
                PerceptionResult<String> result = typed.perceive(input);
                if (result.data() != null && !result.data().isEmpty()) {
                    combined.append("[").append(result.type()).append("] ")
                            .append(result.data()).append("\n");
                }
            } catch (Exception e) {
                log.debug("Perceptor {} failed: {}", p.getClass().getSimpleName(), e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        String data = combined.toString();
        int tokens = Math.max(1, data.length() / 4);

        ObservationRecord record = new ObservationRecord(
                history.size() + 1, phase, data, tokens, elapsed,
                ZonedDateTime.now()
        );
        history.addLast(record);
        if (history.size() > maxHistory) {
            history.removeFirst();
        }

        totalObservations++;
        totalObservationTokens += tokens;
        lastObservationTime = record.timestamp();

        log.debug("PerceptionObserver [{}] {}ms, {} tokens", phase, elapsed, tokens);
        return PerceptionResult.full(data, "composite", 0.8, elapsed, tokens);
    }

    /**
     * Build enriched context for injection into the next LLM call.
     * Includes recent perceptions (last 5) and summary stats.
     */
    public String buildEnrichedContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("[Environment Perception]\n");
        sb.append("Total observations: ").append(totalObservations).append("\n");
        sb.append("Estimated tokens consumed: ").append(totalObservationTokens).append("\n");
        if (lastObservationTime != null) {
            sb.append("Last observation: ").append(lastObservationTime).append("\n");
        }

        sb.append("\n[Recent Observations]\n");
        List<ObservationRecord> recent = new ArrayList<>(history);
        int start = Math.max(0, recent.size() - 5);
        for (int i = start; i < recent.size(); i++) {
            ObservationRecord r = recent.get(i);
            sb.append("- [").append(r.phase()).append("#").append(r.index())
                    .append("] ").append(truncate(r.data(), 200)).append("\n");
        }

        return sb.toString();
    }

    /** Detect significant environmental changes between observations. */
    public List<String> detectChanges() {
        List<String> changes = new ArrayList<>();
        List<ObservationRecord> recent = new ArrayList<>(history);

        if (recent.size() < 2) return changes;

        ObservationRecord last = recent.get(recent.size() - 1);
        ObservationRecord prev = recent.get(recent.size() - 2);

        // Simple heuristic: significant token count change
        if (Math.abs(last.tokens() - prev.tokens()) > 50) {
            changes.add("Significant context change: "
                    + prev.tokens() + " → " + last.tokens() + " tokens");
        }

        // Phase transition
        if (!last.phase().equals(prev.phase())) {
            changes.add("Phase transition: " + prev.phase() + " → " + last.phase());
        }

        return changes;
    }

    // ============ Getters ============

    public int totalObservations() { return totalObservations; }
    public long totalObservationTokens() { return totalObservationTokens; }
    public List<ObservationRecord> recentHistory(int n) {
        List<ObservationRecord> all = new ArrayList<>(history);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    // ============ Types ============

    public record ObservationRecord(int index, String phase, String data, int tokens,
                                     long elapsedMs, ZonedDateTime timestamp) {}

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
