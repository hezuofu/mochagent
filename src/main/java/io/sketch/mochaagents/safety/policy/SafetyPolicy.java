package io.sketch.mochaagents.safety.policy;

import java.util.List;

/**
 * 安全策略 — 定义 Agent 可执行操作的安全边界.
 */
public class SafetyPolicy {

    private final List<String> allowedOperations;
    private final List<String> deniedOperations;
    private final boolean denyByDefault;

    public SafetyPolicy(List<String> allowed, List<String> denied, boolean denyByDefault) {
        this.allowedOperations = List.copyOf(allowed);
        this.deniedOperations = List.copyOf(denied);
        this.denyByDefault = denyByDefault;
    }

    public static SafetyPolicy permissive() {
        return new SafetyPolicy(List.of("*"), List.of(), false);
    }

    public static SafetyPolicy restrictive() {
        return new SafetyPolicy(List.of("read", "search"), List.of("execute", "delete", "write"), true);
    }

    /** 检查操作是否被允许 */
    public boolean isAllowed(String operation) {
        if (deniedOperations.contains(operation)) return false;
        if (deniedOperations.contains("*")) return false;
        if (allowedOperations.contains("*")) return true;
        if (allowedOperations.contains(operation)) return true;
        return !denyByDefault;
    }

    public List<String> allowedOperations() { return allowedOperations; }
    public List<String> deniedOperations() { return deniedOperations; }
}
