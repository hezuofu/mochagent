package io.sketch.mochaagents.safety;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sandboxed tool registry — transparently wraps all registered tools in a Sandbox.
 * <p>Usage: replace {@code new ToolRegistry()} with {@code new SafeToolRegistry(sandbox)}.
 * @author lanxia39@163.com
 */
public class SafeToolRegistry extends ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(SafeToolRegistry.class);

    private final Sandbox sandbox;

    public SafeToolRegistry(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    public SafeToolRegistry() {
        this(new ProcessSandbox());
    }

    @Override
    public void register(Tool tool) {
        Tool wrapped = sandbox.wrap(tool);
        super.register(wrapped);
        log.debug("Registered sandboxed tool: {} (backend: {})", tool.getName(), sandbox.backendName());
    }

    /** Get the underlying sandbox. */
    public Sandbox sandbox() { return sandbox; }
}
