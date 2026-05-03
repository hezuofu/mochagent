package io.sketch.mochaagents.safety;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Sandboxed tool decorator — wraps any Tool, routing execution through a Sandbox.
 * Transparent to the agent — same Tool interface, automatic safety enforcement.
 * @author lanxia39@163.com
 */
public final class SandboxedTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SandboxedTool.class);

    private final Tool delegate;
    private final Sandbox sandbox;

    public SandboxedTool(Tool delegate, Sandbox sandbox) {
        this.delegate = delegate;
        this.sandbox = sandbox;
    }

    @Override public String getName() { return delegate.getName(); }
    @Override public String getDescription() { return delegate.getDescription(); }
    @Override public Map<String, ToolInput> getInputs() { return delegate.getInputs(); }
    @Override public String getOutputType() { return delegate.getOutputType(); }
    @Override public SecurityLevel getSecurityLevel() { return delegate.getSecurityLevel(); }

    @Override
    public Object call(Map<String, Object> arguments) {
        // Pre-execution safety check
        var validation = delegate.validateInput(arguments);
        if (!validation.isValid()) {
            log.warn("Tool '{}' input validation failed: {}", delegate.getName(), validation.getMessage());
            return "REJECTED: " + validation.getMessage();
        }
        var permission = delegate.checkPermissions(arguments);
        if (permission.isDenied()) {
            log.warn("Tool '{}' denied by permission check: {}", delegate.getName(), permission.getMessage());
            return "DENIED: " + permission.getMessage();
        }

        // Execute within sandbox
        long start = System.currentTimeMillis();
        try {
            Object result = sandbox.execute(() -> delegate.call(arguments));
            long ms = System.currentTimeMillis() - start;
            log.debug("Sandboxed '{}': {}ms, result={}",
                    delegate.getName(), ms, result != null ? result.toString().substring(0, Math.min(80, result.toString().length())) : "null");
            return result;
        } catch (Exception e) {
            log.error("Sandboxed '{}' failed: {}", delegate.getName(), e.getMessage());
            return "SANDBOX_ERROR: " + e.getMessage();
        }
    }
}
