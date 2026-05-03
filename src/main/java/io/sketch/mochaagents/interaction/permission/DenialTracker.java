package io.sketch.mochaagents.interaction.permission;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Denial tracker — counts consecutive denials per tool, auto-blocks when exceeded.
 * Pattern from claude-code's denialTracking.ts.
 * @author lanxia39@163.com
 */
public final class DenialTracker {

    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private volatile int maxDenialsBeforeBlock = 3;

    public DenialTracker maxBeforeBlock(int n) { this.maxDenialsBeforeBlock = n; return this; }

    /** Record a denial. Returns true if tool should now be auto-blocked. */
    public boolean recordDenial(String toolName) {
        int count = counts.computeIfAbsent(toolName, k -> new AtomicInteger()).incrementAndGet();
        return count >= maxDenialsBeforeBlock;
    }

    /** Record an approval — resets the denial counter for that tool. */
    public void recordApproval(String toolName) {
        counts.remove(toolName);
    }

    /** Get current denial count for a tool. */
    public int count(String toolName) {
        AtomicInteger c = counts.get(toolName);
        return c != null ? c.get() : 0;
    }

    /** Reset all denial counters. */
    public void reset() { counts.clear(); }
}
