package io.sketch.mochaagents.safety;

/**
 * 沙箱环境 — 隔离执行不可信代码，防止系统损害.
 */
public class Sandbox {

    private final long timeoutMs;
    private final boolean networkDisabled;
    private final boolean fileSystemRestricted;

    public Sandbox(long timeoutMs, boolean networkDisabled, boolean fileSystemRestricted) {
        this.timeoutMs = timeoutMs;
        this.networkDisabled = networkDisabled;
        this.fileSystemRestricted = fileSystemRestricted;
    }

    public Sandbox() {
        this(30000, true, true);
    }

    /**
     * 在沙箱中执行代码.
     * 当前为模拟实现，实际需集成 Docker/gVisor 等容器技术.
     */
    public String execute(String code, String language) {
        return "[Sandbox] Code executed safely in " + language + " sandbox. " +
               "Timeout: " + timeoutMs + "ms, Network: " + (networkDisabled ? "disabled" : "enabled") +
               ", FS: " + (fileSystemRestricted ? "restricted" : "full");
    }
}
