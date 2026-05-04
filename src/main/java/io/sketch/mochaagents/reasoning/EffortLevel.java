package io.sketch.mochaagents.reasoning;

/**
 * Effort level — controls reasoning depth independent of thinking budget.
 *
 * <p>Claude-code pattern: effort is a separate dimension from thinking config.
 * While thinking controls the budget, effort guides the model's reasoning intensity.
 *
 * <ul>
 *   <li>{@code LOW} — quick, straightforward implementation</li>
 *   <li>{@code MEDIUM} — balanced approach</li>
 *   <li>{@code HIGH} — comprehensive reasoning (default)</li>
 *   <li>{@code MAX} — maximum capability, deepest reasoning</li>
 * </ul>
 *
 * @author lanxia39@163.com
 */
public enum EffortLevel {

    LOW(0.3, "Quick implementation"),
    MEDIUM(0.6, "Balanced approach"),
    HIGH(0.85, "Comprehensive reasoning"),
    MAX(1.0, "Maximum capability");

    private final double intensity;
    private final String description;

    EffortLevel(double intensity, String description) {
        this.intensity = intensity;
        this.description = description;
    }

    public double intensity() { return intensity; }
    public String description() { return description; }

    /** Resolve effective effort from environment, settings, and model defaults. */
    public static EffortLevel resolve(String envVar, EffortLevel setting, String modelId) {
        // 1. Environment variable override
        if (envVar != null && !envVar.isEmpty()) {
            try { return valueOf(envVar.toUpperCase()); }
            catch (IllegalArgumentException e) { /* fall through */ }
        }

        // 2. Explicit setting
        if (setting != null) return setting;

        // 3. Model default
        return getDefaultForModel(modelId);
    }

    /** Default effort per model family. */
    public static EffortLevel getDefaultForModel(String modelId) {
        if (modelId == null) return HIGH;
        String lower = modelId.toLowerCase();
        if (lower.contains("opus-4")) return HIGH;  // Opus 4 defaults to HIGH
        if (lower.contains("sonnet")) return HIGH;
        if (lower.contains("haiku")) return MEDIUM;
        return HIGH;
    }

    /**
     * Detect "ultrathink" keywords in user input.
     * Claude-code pattern: "ultrathink" keyword bumps effort to HIGH.
     */
    public static EffortLevel detectKeywordBoost(String userInput, EffortLevel current) {
        if (userInput == null) return current;
        String lower = userInput.toLowerCase();
        if (lower.contains("ultrathink") || lower.contains("think hard")
                || lower.contains("think deeply") || lower.contains("reason carefully")) {
            return current.intensity() < HIGH.intensity() ? HIGH : current;
        }
        return current;
    }
}
