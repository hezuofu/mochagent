package io.sketch.mochaagents.plan;

/**
 * 执行结果.
 */
public final class ExecutionResult {
    private final Object output;
    private final String error;
    private final long durationMs;

    public ExecutionResult(Object output, String error, long durationMs) {
        this.output = output;
        this.error = error;
        this.durationMs = durationMs;
    }

    public Object output() { return output; }
    public String error() { return error; }
    public long durationMs() { return durationMs; }
    public boolean isError() { return error != null && !error.isEmpty(); }

    public static ExecutionResult success(Object output) { return new ExecutionResult(output, null, 0); }
    public static ExecutionResult failure(String error) { return new ExecutionResult(null, error, 0); }
}
