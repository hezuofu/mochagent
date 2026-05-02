package io.sketch.mochaagents.learn;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 经验 — 记录一次完整的输入-输出-反馈循环，用于学习改进.
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public class Experience<I, O> {

    private final String id;
    private final I input;
    private final O output;
    private final O expectedOutput;
    private final double reward;
    private final String feedback;
    private final Instant timestamp;
    private final Map<String, Object> metadata;

    public Experience(I input, O output, O expectedOutput, double reward,
                      String feedback, Map<String, Object> metadata) {
        this.id = UUID.randomUUID().toString();
        this.input = input;
        this.output = output;
        this.expectedOutput = expectedOutput;
        this.reward = reward;
        this.feedback = feedback;
        this.timestamp = Instant.now();
        this.metadata = Map.copyOf(metadata);
    }

    public String id() { return id; }
    public I input() { return input; }
    public O output() { return output; }
    public O expectedOutput() { return expectedOutput; }
    public double reward() { return reward; }
    public String feedback() { return feedback; }
    public Instant timestamp() { return timestamp; }
    public Map<String, Object> metadata() { return metadata; }

    /** 积极经验 */
    public boolean isPositive() { return reward > 0; }

    /** 消极经验 */
    public boolean isNegative() { return reward < 0; }

    public static <I, O> Builder<I, O> builder() { return new Builder<>(); }

    public static class Builder<I, O> {
        private I input;
        private O output;
        private O expectedOutput;
        private double reward;
        private String feedback = "";
        private Map<String, Object> metadata = Map.of();

        public Builder<I, O> input(I input) { this.input = input; return this; }
        public Builder<I, O> output(O output) { this.output = output; return this; }
        public Builder<I, O> expectedOutput(O expected) { this.expectedOutput = expected; return this; }
        public Builder<I, O> reward(double reward) { this.reward = reward; return this; }
        public Builder<I, O> feedback(String feedback) { this.feedback = feedback; return this; }
        public Builder<I, O> metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }

        public Experience<I, O> build() {
            return new Experience<>(input, output, expectedOutput, reward, feedback, metadata);
        }
    }
}
