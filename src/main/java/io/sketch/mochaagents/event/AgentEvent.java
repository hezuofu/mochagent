// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.event;

import java.time.Instant;

/** Unified event — single record type for all agent lifecycle events. */
public record AgentEvent(EventType type, String agentName, Object data,
                         long elapsedMs, Instant timestamp) {

    public AgentEvent(EventType type, String agentName, Object data, long elapsedMs) {
        this(type, agentName, data, elapsedMs, Instant.now());
    }

    public boolean is(EventType t) { return type == t; }
}
