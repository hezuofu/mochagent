package io.sketch.mochaagents.reasoning;

/**
 * 推理步骤 — 记录单步推理.
 */
public final class ReasoningStep {

    private final int index;
    private final String thought;
    private final String conclusion;
    private final double confidence;

    public ReasoningStep(int index, String thought, String conclusion, double confidence) {
        this.index = index;
        this.thought = thought;
        this.conclusion = conclusion;
        this.confidence = confidence;
    }

    public int index() { return index; }
    public String thought() { return thought; }
    public String conclusion() { return conclusion; }
    public double confidence() { return confidence; }
}
