// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.interaction;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session interaction state — permissions, denials, mode.
 *
 * <p>One instance per connection (CLI terminal, desktop window, WebSocket).
 *
 * @author lanxia39@163.com
 */
public class Session {

    private final String id;
    private final Instant createdAt = Instant.now();
    private InteractionMode mode = InteractionMode.COLLABORATIVE;

    // Session-scoped allow set (Bash, FileWrite, etc.)
    private final Set<String> sessionAllowed = ConcurrentHashMap.newKeySet();
    // Per-tool denial tracking
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> denials = new ConcurrentHashMap<>();
    // Auto-block threshold
    private volatile int maxDenialsBeforeBlock = 5;

    public Session(String id) { this.id = id; }

    public String id() { return id; }
    public Instant createdAt() { return createdAt; }
    public InteractionMode mode() { return mode; }
    public Session mode(InteractionMode m) { this.mode = m; return this; }
    public Session maxDenialsBeforeBlock(int n) { this.maxDenialsBeforeBlock = n; return this; }

    /** Grant session-scoped permission. */
    public void allowSession(String toolName) { sessionAllowed.add(toolName); }

    /** Check if session-scoped permission exists. */
    public boolean isSessionAllowed(String toolName) { return sessionAllowed.contains(toolName); }

    /** Record a denial. Returns true if agent should be blocked. */
    public boolean recordDenial(String toolName) {
        int count = denials.computeIfAbsent(toolName, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
        return count >= maxDenialsBeforeBlock;
    }

    /** Clear denials for a tool. */
    public void clearDenials(String toolName) { denials.remove(toolName); }

    /** Reset all session state. */
    public void reset() {
        sessionAllowed.clear();
        denials.clear();
        mode = InteractionMode.COLLABORATIVE;
    }
}
