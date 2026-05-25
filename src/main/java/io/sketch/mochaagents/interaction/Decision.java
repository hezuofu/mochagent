// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.interaction;

import java.util.Map;

/**
 * Permission decision from the {@link DecisionPipeline}.
 *
 * @author lanxia39@163.com
 */
public sealed interface Decision {

    /** Allow — execute immediately. */
    record Allow(String reason, boolean sessionScoped) implements Decision {}

    /** Deny — refuse execution. */
    record Deny(String reason) implements Decision {}

    /** Ask — require user/classifier approval. */
    record Ask(String prompt, Map<String, Object> context) implements Decision {}

    /** Hard-deny — can never be bypassed by any mode. */
    record HardDeny(String reason) implements Decision {}

    // ── Factory ──

    static Decision allow(String reason) { return new Allow(reason, false); }
    static Decision allow(String reason, boolean scoped) { return new Allow(reason, scoped); }
    static Decision deny(String reason) { return new Deny(reason); }
    static Decision ask(String prompt) { return new Ask(prompt, Map.of()); }
    static Decision hardDeny(String reason) { return new HardDeny(reason); }
}
