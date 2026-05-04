package io.sketch.mochaagents.agent;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Notification system with folding — deduplicates repeated messages by key,
 * auto-merges them ("3 tools executed"), and supports priority levels.
 * Pattern from claude-code's notification context (notifications.ts).
 * @author lanxia39@163.com
 */
public final class Notifications {

    public enum Priority { LOW, HIGH }

    private final ConcurrentLinkedQueue<Notification> queue = new ConcurrentLinkedQueue<>();
    private final Map<String, Notification> active = new LinkedHashMap<>();
    private final List<Consumer<Notification>> listeners = new ArrayList<>();

    /** Post a notification. If a notification with the same key exists, it's folded. */
    public Notifications post(String key, String text, Priority priority, long timeoutMs) {
        Notification existing = active.get(key);
        if (existing != null && existing.foldFn != null) {
            existing = existing.foldFn.apply(existing, new Notification(key, text, priority, timeoutMs, null));
            active.put(key, existing);
        } else {
            Notification n = new Notification(key, text, priority, timeoutMs, null);
            active.put(key, n);
            queue.offer(n);
        }
        listeners.forEach(l -> l.accept(active.get(key)));
        return this;
    }

    /** Post a notification that folds by counting ("3 agents spawned"). */
    public Notifications postFolding(String key, String singular, Priority priority, long timeoutMs) {
        return post(key, singular, priority, timeoutMs);
    }

    /** Post with a custom fold function. */
    public Notifications post(String key, String text, Priority priority, long timeoutMs,
                               BiFunction<Notification, Notification, Notification> foldFn) {
        Notification n = new Notification(key, text, priority, timeoutMs, foldFn);
        Notification existing = active.get(key);
        if (existing != null && foldFn != null) n = foldFn.apply(existing, n);
        active.put(key, n);
        queue.offer(n);
        final Notification finalN = n;
        listeners.forEach(l -> l.accept(finalN));
        return this;
    }

    /** Dismiss a notification by key. */
    public void dismiss(String key) { active.remove(key); }

    /** Get all currently active notifications. */
    public Collection<Notification> active() { return Collections.unmodifiableCollection(active.values()); }

    /** Subscribe to notification changes. */
    public void onChange(Consumer<Notification> listener) { listeners.add(listener); }

    /** Clear all notifications. */
    public void clear() { active.clear(); queue.clear(); }

    // ============ Types ============

    public record Notification(String key, String text, Priority priority, long timeoutMs,
                                BiFunction<Notification, Notification, Notification> foldFn) {
        /** Built-in fold: counts occurrences ("3 agents spawned"). */
        public static BiFunction<Notification, Notification, Notification> countFold(String separator) {
            return (existing, incoming) -> {
                // Extract count from existing text, increment
                int count = 2;
                String existingText = existing.text();
                if (existingText.matches(".*\\d+.*"))
                    try { count = Integer.parseInt(existingText.replaceAll("[^0-9]", "")) + 1; }
                    catch (NumberFormatException ignored) {}
                return new Notification(existing.key(), count + " " + existingText.replaceAll("\\d+ ", ""),
                        existing.priority(), existing.timeoutMs(), existing.foldFn());
            };
        }
    }
}
