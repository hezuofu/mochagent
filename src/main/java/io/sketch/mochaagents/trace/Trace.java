// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.trace;

import java.util.Map;

/**
 * Unified observability — events, metrics, and cost tracking.
 *
 * <p>All agent telemetry flows through this one interface.
 * Default no-op implementation via {@link #noop()}.
  * @author lanxia39@163.com
 */
public interface Trace {

    void event(String name, Map<String, Object> props);

    default void metric(String name, double value) {}

    default void cost(double usd, long inputTokens, long outputTokens) {}

    /** No-op trace — safe default. */
    static Trace noop() { return (name, props) -> {}; }
}
