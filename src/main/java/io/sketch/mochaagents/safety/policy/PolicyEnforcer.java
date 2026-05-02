package io.sketch.mochaagents.safety.policy;

/**
 * 策略执行器 — 强制执行安全策略，拦截违规操作.
 */
public class PolicyEnforcer {

    private SafetyPolicy policy;

    public PolicyEnforcer(SafetyPolicy policy) {
        this.policy = policy;
    }

    public PolicyEnforcer() {
        this(SafetyPolicy.restrictive());
    }

    /** 执行前检查 */
    public PolicyResult enforce(String operation) {
        boolean allowed = policy.isAllowed(operation);
        return new PolicyResult(allowed, allowed ? "Allowed" : "Denied by policy", operation);
    }

    /** 更新策略 */
    public void updatePolicy(SafetyPolicy newPolicy) {
        this.policy = newPolicy;
    }

    public SafetyPolicy getPolicy() { return policy; }

    public record PolicyResult(boolean allowed, String reason, String operation) {}
}
