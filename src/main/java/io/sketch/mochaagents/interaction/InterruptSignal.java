// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.interaction;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-session interrupt — hermes-agent thread-scoped pattern.
 *
 * <p>Multiple sessions share one process, but each session only sees its own interrupt.
 *
 * @author lanxia39@163.com
 */
public final class InterruptSignal {

    private static final ConcurrentHashMap<String, AtomicReference<Reason>> sessions = new ConcurrentHashMap<>();

    public enum Reason { USER_REQUEST, TIMEOUT, SYSTEM_SHUTDOWN }

    /** Signal interrupt for a session. */
    public static void fire(String sessionId, Reason reason) {
        sessions.computeIfAbsent(sessionId, k -> new AtomicReference<>()).set(reason);
    }

    /** Clear interrupt for a session. */
    public static void clear(String sessionId) {
        AtomicReference<Reason> ref = sessions.get(sessionId);
        if (ref != null) ref.set(null);
    }

    /** Check if current session is interrupted. */
    public static boolean isInterrupted(String sessionId) {
        AtomicReference<Reason> ref = sessions.get(sessionId);
        return ref != null && ref.get() != null;
    }

    /** Get the interrupt reason, or null. */
    public static Reason reason(String sessionId) {
        AtomicReference<Reason> ref = sessions.get(sessionId);
        return ref != null ? ref.get() : null;
    }

    /** Remove session (on disconnect). */
    public static void remove(String sessionId) {
        sessions.remove(sessionId);
    }
}
