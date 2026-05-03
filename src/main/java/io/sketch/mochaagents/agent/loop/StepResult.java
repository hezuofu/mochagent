package io.sketch.mochaagents.agent.loop;

/**
 * 单步执行结果 — 记录每一步的状态、动作与产出.
 * @author lanxia39@163.com
 */
public final class StepResult {

    private final int stepNumber;
    private final LoopState state;
    private final String observation;
    private final String action;
    private final String output;
    private final String error;
    private final long durationMs;

    private StepResult(Builder builder) {
        this.stepNumber = builder.stepNumber;
        this.state = builder.state;
        this.observation = builder.observation;
        this.action = builder.action;
        this.output = builder.output;
        this.error = builder.error;
        this.durationMs = builder.durationMs;
    }

    public int stepNumber() { return stepNumber; }
    public LoopState state() { return state; }
    public String observation() { return observation; }
    public String action() { return action; }
    public String output() { return output; }
    public String error() { return error; }
    public long durationMs() { return durationMs; }
    public boolean hasError() { return error != null && !error.isEmpty(); }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int stepNumber;
        private LoopState state = LoopState.ACT;
        private String observation = "";
        private String action = "";
        private String output = "";
        private String error = "";
        private long durationMs;

        public Builder stepNumber(int stepNumber) { this.stepNumber = stepNumber; return this; }
        public Builder state(LoopState state) { this.state = state; return this; }
        public Builder observation(String observation) { this.observation = observation; return this; }
        public Builder action(String action) { this.action = action; return this; }
        public Builder output(String output) { this.output = output; return this; }
        public Builder error(String error) { this.error = error; return this; }
        public Builder durationMs(long durationMs) { this.durationMs = durationMs; return this; }

        public StepResult build() {
            return new StepResult(this);
        }
    }
}
