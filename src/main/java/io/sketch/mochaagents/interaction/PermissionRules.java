// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.interaction;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Hierarchical permission rules — Claude Code pattern with content matching.
 *
 * <p>Source priority: POLICY > LOCAL > PROJECT > USER.
 *
 * @author lanxia39@163.com
 */
public final class PermissionRules {

    public enum Behavior { ALLOW, DENY, ASK }
    public enum Source { USER, PROJECT, LOCAL, POLICY }

    private final List<Rule> rules = new ArrayList<>();
    private Behavior defaultBehavior = Behavior.ASK;

    /** Add a tool-name-only rule. */
    public PermissionRules add(String toolPattern, Behavior behavior, Source source) {
        return add(toolPattern, null, behavior, source, "");
    }

    /** Add a content-specific rule. e.g. toolPattern="bash", contentPattern="npm install.*" */
    public PermissionRules add(String toolPattern, String contentPattern, Behavior behavior, Source source, String reason) {
        rules.add(new Rule(
                Pattern.compile(wildcardToRegex(toolPattern)),
                contentPattern != null ? Pattern.compile(contentPattern, Pattern.CASE_INSENSITIVE) : null,
                behavior, source, reason));
        return this;
    }

    public PermissionRules defaultBehavior(Behavior b) { this.defaultBehavior = b; return this; }

    /** Simple tool-name-only resolve (backward compat). */
    public Behavior resolve(String toolName) {
        var deny = matchDeny(toolName, null);
        if (deny != null) {
            return Behavior.DENY;
        }
        var allow = matchAllow(toolName, null);
        if (allow != null) {
            return Behavior.ALLOW;
        }
        var ask = matchAsk(toolName, null);
        if (ask != null) {
            return Behavior.ASK;
        }
        return defaultBehavior;
    }

    /** Find matching deny rule. */
    public Rule matchDeny(String toolName, String content) { return match(toolName, content, Behavior.DENY); }

    /** Find matching allow rule. */
    public Rule matchAllow(String toolName, String content) { return match(toolName, content, Behavior.ALLOW); }

    /** Find matching ask rule. */
    public Rule matchAsk(String toolName, String content) { return match(toolName, content, Behavior.ASK); }

    /** Default decision based on mode. */
    public Decision defaultDecision(ToolUse use) {
        return switch (defaultBehavior) {
            case ALLOW -> Decision.allow("default allow");
            case DENY -> Decision.deny("default deny");
            case ASK -> Decision.ask("Permission required for: " + use.toolName());
        };
    }

    private Rule match(String toolName, String content, Behavior behavior) {
        Rule best = null;
        for (Rule r : rules) {
            if (r.behavior != behavior) {
                continue;
            }
            if (!r.toolPattern.matcher(toolName).matches()) {
                continue;
            }
            if (r.contentPattern != null && content != null && !r.contentPattern.matcher(content).find()) {
                continue;
            }
            if (best == null || r.source.ordinal() > best.source.ordinal()) {
                best = r;
            }
        }
        return best;
    }

    private static String wildcardToRegex(String pattern) {
        StringBuilder sb = new StringBuilder("^");
        for (String part : pattern.split("\\*", -1)) {
            sb.append(Pattern.quote(part)).append(".*");
        }
        sb.setLength(sb.length() - 2);
        return sb.append("$").toString();
    }

    public record Rule(Pattern toolPattern, Pattern contentPattern,
                       Behavior behavior, Source source, String reason) {}
}
