package io.sketch.mochaagents.plugin;

/**
 * 插件状态枚举.
 * @author lanxia39@163.com
 */
public enum PluginState {
    /** 已启用，Skills/MCP/LSP 等组件可用. */
    ENABLED,
    /** 已禁用，用户可重新启用. */
    DISABLED,
    /** 不可用（如缺少系统依赖），完全隐藏. */
    UNAVAILABLE
}
