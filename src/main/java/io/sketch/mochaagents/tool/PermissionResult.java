package io.sketch.mochaagents.tool;

import java.util.Map;

/**
 * 工具权限检查结果 — 对齐 claude-code 的 PermissionResult.
 *
 * <pre>
 *   ALLOW  → 直接执行
 *   DENY   → 拒绝执行
 *   ASK    → 需要用户确认
 * </pre>
 */
public final class PermissionResult {

    public enum Behavior { ALLOW, DENY, ASK }

    private final Behavior behavior;
    private final String message;
    private final Map<String, Object> updatedInput;
    private final String decisionReason;

    private PermissionResult(Behavior behavior, String message,
                             Map<String, Object> updatedInput, String decisionReason) {
        this.behavior = behavior;
        this.message = message;
        this.updatedInput = updatedInput;
        this.decisionReason = decisionReason;
    }

    /** 直接允许，传入可选修改后的输入. */
    public static PermissionResult allow(Map<String, Object> updatedInput) {
        return new PermissionResult(Behavior.ALLOW, null, updatedInput, null);
    }

    /** 直接允许，含决策原因. */
    public static PermissionResult allow(Map<String, Object> updatedInput, String reason) {
        return new PermissionResult(Behavior.ALLOW, null, updatedInput, reason);
    }

    /** 拒绝执行. */
    public static PermissionResult deny(String message) {
        return new PermissionResult(Behavior.DENY, message, null, null);
    }

    /** 拒绝执行，含决策原因. */
    public static PermissionResult deny(String message, String reason) {
        return new PermissionResult(Behavior.DENY, message, null, reason);
    }

    /** 需要用户确认. */
    public static PermissionResult ask(String message) {
        return new PermissionResult(Behavior.ASK, message, null, null);
    }

    /** 需要用户确认，含决策原因. */
    public static PermissionResult ask(String message, String reason) {
        return new PermissionResult(Behavior.ASK, message, null, reason);
    }

    public Behavior getBehavior() { return behavior; }
    public String getMessage() { return message; }
    public Map<String, Object> getUpdatedInput() { return updatedInput; }
    public String getDecisionReason() { return decisionReason; }
    public boolean isAllowed() { return behavior == Behavior.ALLOW; }
    public boolean isDenied() { return behavior == Behavior.DENY; }
    public boolean requiresUserInteraction() { return behavior == Behavior.ASK; }

    @Override
    public String toString() {
        return "PermissionResult{" + behavior + (message != null ? ", " + message : "") + "}";
    }
}
