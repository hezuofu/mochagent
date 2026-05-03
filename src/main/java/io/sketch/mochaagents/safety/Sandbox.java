package io.sketch.mochaagents.safety;

/**
 * Code execution sandbox — isolates untrusted code from the host system.
 *
 * <p>Implementations range from no-op (no isolation) to process-level to
 * full container (Docker / gVisor). Choose based on your threat model.
 * @author lanxia39@163.com
 */
public interface Sandbox {

    /**
     * Execute code in the sandbox.
     * @param code     source code to execute
     * @param language programming language (python, javascript, etc.)
     * @return execution output
     */
    String execute(String code, String language);

    /**
     * Execute a generic operation within sandbox constraints.
     * Default calls the supplier directly (no isolation).
     */
    @SuppressWarnings("unchecked")
    default <T> T execute(java.util.function.Supplier<T> operation) {
        return operation.get();
    }

    /** Wrap a tool for sandboxed execution. */
    default io.sketch.mochaagents.tool.Tool wrap(io.sketch.mochaagents.tool.Tool tool) {
        return new SandboxedTool(tool, this);
    }

    /** Human-readable name of this sandbox backend. */
    default String backendName() {
        return getClass().getSimpleName();
    }
}
