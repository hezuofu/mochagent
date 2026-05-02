package io.sketch.mochaagents.core.loop.reflection;

/**
 * 自我批评 — 对单步执行的分析与评分.
 */
public final class SelfCritique {

    private final int stepNumber;
    private final String analysis;
    private final double confidence;
    private final boolean needsImprovement;
    private final String suggestion;

    private SelfCritique(Builder builder) {
        this.stepNumber = builder.stepNumber;
        this.analysis = builder.analysis;
        this.confidence = builder.confidence;
        this.needsImprovement = builder.needsImprovement;
        this.suggestion = builder.suggestion;
    }

    public int stepNumber() { return stepNumber; }
    public String analysis() { return analysis; }
    public double confidence() { return confidence; }
    public boolean needsImprovement() { return needsImprovement; }
    public String suggestion() { return suggestion; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int stepNumber;
        private String analysis = "";
        private double confidence = 1.0;
        private boolean needsImprovement;
        private String suggestion = "";

        public Builder stepNumber(int stepNumber) { this.stepNumber = stepNumber; return this; }
        public Builder analysis(String analysis) { this.analysis = analysis; return this; }
        public Builder confidence(double confidence) { this.confidence = confidence; return this; }
        public Builder needsImprovement(boolean needsImprovement) { this.needsImprovement = needsImprovement; return this; }
        public Builder suggestion(String suggestion) { this.suggestion = suggestion; return this; }

        public SelfCritique build() {
            return new SelfCritique(this);
        }
    }
}
