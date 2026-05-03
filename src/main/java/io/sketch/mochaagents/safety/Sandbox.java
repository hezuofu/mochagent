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
     *
     * @param code     source code to execute
     * @param language programming language (python, javascript, etc.)
     * @return execution output
     * @throws SandboxException if execution fails or is blocked
     */
    String execute(String code, String language);

    /** Human-readable name of this sandbox backend. */
    default String backendName() {
        return getClass().getSimpleName();
    }
}
