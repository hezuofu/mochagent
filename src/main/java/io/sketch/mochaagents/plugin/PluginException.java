package io.sketch.mochaagents.plugin;

/**
 * 插件异常 — 对齐 claude-code 的 PluginError discriminated union.
 * 简化版，涵盖常见错误类型。
 */
public class PluginException extends RuntimeException {

    private final String type;

    public PluginException(String type, String message) {
        super(message);
        this.type = type;
    }

    public PluginException(String type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    public String type() { return type; }

    // ---- 工厂方法 ----

    public static PluginException notFound(String pluginId, String marketplace) {
        return new PluginException("plugin-not-found",
                "Plugin " + pluginId + " not found in marketplace " + marketplace);
    }

    public static PluginException configInvalid(String plugin, String serverName, String error) {
        return new PluginException("config-invalid",
                "Plugin " + plugin + " has invalid config for " + serverName + ": " + error);
    }

    public static PluginException disabled(String plugin) {
        return new PluginException("plugin-disabled",
                "Plugin " + plugin + " is disabled");
    }

    public static PluginException loadFailed(String plugin, String reason) {
        return new PluginException("load-failed",
                "Failed to load plugin " + plugin + ": " + reason);
    }

    public static PluginException generic(String message) {
        return new PluginException("generic-error", message);
    }
}
