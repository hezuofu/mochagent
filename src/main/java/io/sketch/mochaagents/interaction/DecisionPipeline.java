// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.interaction;

/**
 * Decision pipeline — Claude Code rule engine + hermes safety gate.
 *
 * <p>Evaluation order (first match wins):
 * <ol>
 *   <li>{@link SafetyGate} — hardline patterns, never bypassed</li>
 *   <li>Deny rules — content-specific deny</li>
 *   <li>Allow rules — content-specific allow</li>
 *   <li>Ask rules — force prompt regardless of mode</li>
 *   <li>{@link PermissionRules} — mode-based default</li>
 * </ol>
 *
 * @author lanxia39@163.com
 */
@FunctionalInterface
public interface DecisionPipeline {

    Decision evaluate(ToolUse request, PermissionRules rules);

    /** Create a pipeline with hardline safety gate. */
    static DecisionPipeline standard() {
        return (use, rules) -> {
            // 1. Hardline — never bypass
            Decision hd = SafetyLine.hardBlock(use);
            if (hd instanceof Decision.HardDeny) {
                return hd;
            }

            // 2. Deny rules (content-specific)
            PermissionRules.Rule deny = rules.matchDeny(use.toolName(), use.content());
            if (deny != null) {
                return Decision.deny(deny.reason());
            }

            // 3. Allow rules (content-specific)
            PermissionRules.Rule allow = rules.matchAllow(use.toolName(), use.content());
            if (allow != null) {
                return Decision.allow(allow.reason());
            }

            // 4. Ask rules (force prompt)
            PermissionRules.Rule ask = rules.matchAsk(use.toolName(), use.content());
            if (ask != null) {
                return Decision.ask(ask.reason());
            }

            // 5. Mode-based default
            return rules.defaultDecision(use);
        };
    }
}
