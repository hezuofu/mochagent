package io.sketch.mochaagents.reasoning;

/**
 * Thinking configuration matching claude-code's thinking parameter system.
 *
 * <p>Controls how much the model "thinks" before acting:
 * <ul>
 *   <li>{@code ADAPTIVE} — model decides its own thinking budget (Claude 4.6+)</li>
 *   <li>{@code ENABLED} — fixed thinking budget in tokens</li>
 *   <li>{@code DISABLED} — no thinking blocks, act immediately</li>
 * </ul>
 *
 * <p>Default is ADAPTIVE for models that support it.
 *
 * @author lanxia39@163.com
 */
public final class ThinkingConfig {

    public enum Type { ADAPTIVE, ENABLED, DISABLED }

    private final Type type;
    private final int budgetTokens; // only for ENABLED

    private ThinkingConfig(Type type, int budgetTokens) {
        this.type = type;
        this.budgetTokens = budgetTokens;
    }

    public Type type() { return type; }
    public int budgetTokens() { return budgetTokens; }

    /** Model decides its own thinking budget (default for Claude 4.6+). */
    public static ThinkingConfig adaptive() {
        return new ThinkingConfig(Type.ADAPTIVE, 0);
    }

    /** Fixed thinking budget. */
    public static ThinkingConfig enabled(int budgetTokens) {
        if (budgetTokens <= 0) throw new IllegalArgumentException("budgetTokens must be > 0");
        return new ThinkingConfig(Type.ENABLED, budgetTokens);
    }

    /** No thinking — act immediately. */
    public static ThinkingConfig disabled() {
        return new ThinkingConfig(Type.DISABLED, 0);
    }

    /** Resolve the effective thinking config for a given model. */
    public static ThinkingConfig resolveForModel(String modelId) {
        if (modelId == null) return disabled();

        String lower = modelId.toLowerCase();
        // Claude 4.6+ supports adaptive thinking
        if (lower.contains("claude-opus-4") || lower.contains("claude-sonnet-4")
                || lower.contains("claude-haiku-4")) {
            if (isEnvTruthy("MOCHA_DISABLE_ADAPTIVE_THINKING")) {
                return enabled(4096);
            }
            return adaptive();
        }

        // Claude 4.5 and below: budget-based thinking
        if (lower.contains("claude")) {
            return enabled(getMaxThinkingTokens(modelId));
        }

        // Other models: disabled by default
        return disabled();
    }

    /** Get max thinking tokens for a model. */
    public static int getMaxThinkingTokens(String modelId) {
        if (modelId == null) return 2048;
        String lower = modelId.toLowerCase();
        if (lower.contains("opus")) return 8192;
        if (lower.contains("sonnet")) return 4096;
        if (lower.contains("haiku")) return 2048;
        return 2048;
    }

    /** Apply thinking config to API parameters. */
    public java.util.Map<String, Object> toApiParams() {
        return switch (type) {
            case ADAPTIVE -> java.util.Map.of("type", "adaptive");
            case ENABLED -> java.util.Map.of("type", "enabled", "budget_tokens", budgetTokens);
            case DISABLED -> java.util.Map.of("type", "disabled");
        };
    }

    private static boolean isEnvTruthy(String name) {
        String val = System.getenv(name);
        return "1".equals(val) || "true".equalsIgnoreCase(val);
    }

    @Override
    public String toString() {
        return "ThinkingConfig{" + type + (type == Type.ENABLED ? ", budget=" + budgetTokens : "") + "}";
    }
}
