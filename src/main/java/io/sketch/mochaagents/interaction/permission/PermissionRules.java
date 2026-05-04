package io.sketch.mochaagents.interaction.permission;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Hierarchical permission rules — pattern-based allow/deny/ask with source priority.
 *
 * <p>Pattern adapted from claude-code's ToolPermissionContext.
 * Source priority: policySettings > localSettings > projectSettings > userSettings > default
 * @author lanxia39@163.com
 */
public final class PermissionRules {

    public enum Behavior { ALLOW, DENY, ASK }
    public enum Source { USER, PROJECT, LOCAL, POLICY }

    private final List<Rule> rules = new ArrayList<>();
    private volatile Behavior defaultBehavior = Behavior.ASK;

    /** Add a rule at a specific source level. Higher-priority sources win. */
    public PermissionRules add(String toolPattern, Behavior behavior, Source source) {
        rules.add(new Rule(Pattern.compile(wildcardToRegex(toolPattern)), behavior, source));
        return this;
    }

    /** Set the default behavior when no rule matches. */
    public PermissionRules defaultBehavior(Behavior b) { this.defaultBehavior = b; return this; }

    /** Resolve the permission decision for a given tool name. */
    public Behavior resolve(String toolName) {
        // Sort by source priority (POLICY highest), then by insertion order
        Rule best = null;
        for (Rule r : rules) {
            if (!r.pattern.matcher(toolName).matches()) continue;
            if (best == null || r.source.ordinal() > best.source.ordinal()) best = r;
        }
        return best != null ? best.behavior : defaultBehavior;
    }

    private static String wildcardToRegex(String pattern) {
        StringBuilder sb = new StringBuilder("^");
        for (String part : pattern.split("\\*", -1)) {
            sb.append(Pattern.quote(part));
            sb.append(".*");
        }
        sb.setLength(sb.length() - 2); // remove trailing ".*"
        return sb.append("$").toString();
    }

    private record Rule(Pattern pattern, Behavior behavior, Source source) {}
}
