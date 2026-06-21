// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.orchestration;

/**
 * Execution policy — retry, timeout, and fallback for orchestrated tasks.
 *
 * @author lanxia39@163.com
 */
public record ExecutionPolicy(
        int maxRetries,
        long timeoutMs,
        String fallbackAgentId
) {
    public static final ExecutionPolicy DEFAULT = new ExecutionPolicy(1, 120_000, null);
    public static final ExecutionPolicy LENIENT = new ExecutionPolicy(3, 300_000, null);
    public static final ExecutionPolicy STRICT = new ExecutionPolicy(0, 60_000, null);

    public static ExecutionPolicy of(int maxRetries, long timeoutMs) {
        return new ExecutionPolicy(maxRetries, timeoutMs, null);
    }
}
