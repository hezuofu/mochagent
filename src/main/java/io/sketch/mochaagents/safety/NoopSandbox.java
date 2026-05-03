package io.sketch.mochaagents.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * No-op sandbox — executes code in the current JVM process with zero isolation.
 *
 * <p>Only suitable for trusted code in development. For untrusted code use
 * {@code ProcessSandbox} (subprocess) or a container-based backend.
 * @author lanxia39@163.com
 */
public final class NoopSandbox implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(NoopSandbox.class);

    private static final String WARNING =
            "*** WARNING: executing code with NO isolation in the current JVM process. "
          + "This is only safe for trusted code in development environments.";

    private final long timeoutMs;
    private final boolean networkDisabled;
    private final boolean fileSystemRestricted;

    public NoopSandbox(long timeoutMs, boolean networkDisabled, boolean fileSystemRestricted) {
        this.timeoutMs = timeoutMs;
        this.networkDisabled = networkDisabled;
        this.fileSystemRestricted = fileSystemRestricted;
    }

    public NoopSandbox() {
        this(30_000, true, true);
    }

    @Override
    public String execute(String code, String language) {
        log.warn(WARNING);
        return "[NoopSandbox] Code NOT executed — no execution backend configured. "
                + "Language: " + language + ", code length: " + code.length() + " chars. "
                + "Configure a real sandbox backend to enable code execution.";
    }
}
