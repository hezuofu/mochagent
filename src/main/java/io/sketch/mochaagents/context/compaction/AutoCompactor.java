// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.context.compaction;

import io.sketch.mochaagents.context.Context;
import io.sketch.mochaagents.model.Model;
import io.sketch.mochaagents.model.ModelRequest;
import io.sketch.mochaagents.model.ModelResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Auto-compactor — Claude Code autoCompact with circuit breaker.
 *
 * <p>After 3 consecutive compaction failures, the circuit opens
 * and compaction is disabled for the remainder of the session.
 *
 * @author lanxia39@163.com
 */
public class AutoCompactor {

    private static final Logger log = LoggerFactory.getLogger(AutoCompactor.class);
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final Model model;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile boolean circuitOpen;

    public AutoCompactor(Model model) { this.model = model; }

    /** Summarize context — returns compacted text or null on failure. */
    public String compact(Context ctx) {
        if (circuitOpen) return null;

        try {
            var chunks = ctx.getContext();
            if (chunks.size() < 4) return null; // too small to compact

            String combined = chunks.stream()
                    .map(c -> c.role() + ": " + c.content())
                    .collect(Collectors.joining("\n"));

            ModelRequest req = ModelRequest.builder()
                    .addMessage("system", "Summarize this conversation. Keep key facts, decisions, and tool results.")
                    .addMessage("user", combined)
                    .maxTokens(1024).temperature(0.3)
                    .build();

            ModelResponse resp = model.complete(req);
            consecutiveFailures.set(0);
            log.info("Compacted {} chunks → {} tokens", chunks.size(), resp.content().length() / 4);
            return resp.content();

        } catch (Exception e) {
            int failures = consecutiveFailures.incrementAndGet();
            log.warn("Compaction failed ({}/{})", failures, MAX_CONSECUTIVE_FAILURES);
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                circuitOpen = true;
                log.error("Compaction circuit OPEN — disabled for session");
            }
            return null;
        }
    }

    public boolean isCircuitOpen() { return circuitOpen; }
    public void reset() { circuitOpen = false; consecutiveFailures.set(0); }
}
