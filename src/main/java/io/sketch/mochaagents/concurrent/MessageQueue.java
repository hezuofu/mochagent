// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Thread-safe priority message queue — Claude Code messageQueueManager pattern.
 *
 * <p>Dequeue returns highest-priority command first.
 * Within same priority, FIFO order is preserved.
 *
 * @author lanxia39@163.com
 */
public class MessageQueue<T> {

    private static final Logger log = LoggerFactory.getLogger(MessageQueue.class);

    private final CopyOnWriteArrayList<Entry<T>> entries = new CopyOnWriteArrayList<>();

    public record Entry<T>(T command, MessagePriority priority, String source) {}

    /** Enqueue with default NEXT priority. */
    public void enqueue(T command, String source) {
        enqueue(command, MessagePriority.NEXT, source);
    }

    /** Enqueue with explicit priority. */
    public void enqueue(T command, MessagePriority priority, String source) {
        entries.add(new Entry<>(command, priority, source));
        log.debug("Enqueued [{}]: {} from {}", priority, command, source);
    }

    /** Enqueue as LATER for async notifications. */
    public void enqueueLater(T command, String source) {
        enqueue(command, MessagePriority.LATER, source);
    }

    /** Dequeue highest-priority entry, or null if empty. */
    public Entry<T> dequeue() {
        return dequeue(e -> true);
    }

    /** Dequeue highest-priority entry matching filter. */
    public Entry<T> dequeue(Predicate<Entry<T>> filter) {
        Entry<T> best = null;
        int bestIdx = -1, bestOrder = Integer.MAX_VALUE;

        for (int i = 0; i < entries.size(); i++) {
            Entry<T> e = entries.get(i);
            if (!filter.test(e)) continue;
            if (e.priority().order < bestOrder) {
                best = e; bestIdx = i; bestOrder = e.priority().order;
            }
        }
        if (bestIdx >= 0) {
            entries.remove(bestIdx);
            log.debug("Dequeued [{}]: {}", best.priority(), best.command());
        }
        return best;
    }

    /** Drain ALL entries of the highest available priority. */
    public java.util.List<Entry<T>> drainHighest() {
        java.util.List<Entry<T>> result = new LinkedList<>();
        Entry<T> e;
        while ((e = dequeue()) != null) result.add(e);
        return result;
    }

    public int size() { return entries.size(); }
    public boolean isEmpty() { return entries.isEmpty(); }
    public void clear() { entries.clear(); }
}
