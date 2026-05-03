package io.sketch.mochaagents.plugin;

import io.sketch.mochaagents.skill.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件管理器 — 对齐 claude-code 的 builtinPlugins registry.
 *
 * <p>管理插件生命周期: 注册、启用/禁用、组件分发。
 * 插件 ID 使用 {@code name@builtin} 格式。
 * @author lanxia39@163.com
 */
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);
    static final String BUILTIN_SUFFIX = "@builtin";

    private final Map<String, PluginDescriptor> plugins = new ConcurrentHashMap<>();
    private final Map<String, Boolean> enabledState = new ConcurrentHashMap<>();

    // ==================== 注册 ====================

    /**
     * 注册插件描述符.
     * 初始启用状态由 defaultEnabled 决定。
     */
    public void register(PluginDescriptor descriptor) {
        plugins.put(descriptor.name(), descriptor);

        // Set initial state
        boolean initialEnabled = descriptor.defaultEnabled() && descriptor.isAvailable();
        enabledState.put(descriptor.name(), initialEnabled);

        log.debug("Plugin registered: {} (enabled={})", descriptor.name(), initialEnabled);
    }

    /** 注销插件. */
    public void unregister(String name) {
        plugins.remove(name);
        enabledState.remove(name);
        log.debug("Plugin unregistered: {}", name);
    }

    // ==================== 启用/禁用 ====================

    /** 启用插件. 不存在的插件抛出异常. */
    public void enable(String name) {
        PluginDescriptor desc = plugins.get(name);
        if (desc == null) {
            throw PluginException.notFound(name, "builtin");
        }
        if (!desc.isAvailable()) {
            throw PluginException.disabled(name);
        }
        enabledState.put(name, true);
        log.debug("Plugin enabled: {}", name);
    }

    /** 禁用插件. */
    public void disable(String name) {
        if (!plugins.containsKey(name)) {
            throw PluginException.notFound(name, "builtin");
        }
        enabledState.put(name, false);
        log.debug("Plugin disabled: {}", name);
    }

    /** 检查插件是否已启用. */
    public boolean isEnabled(String name) {
        return enabledState.getOrDefault(name, false);
    }

    // ==================== 查询 ====================

    /** 获取插件描述符. */
    public PluginDescriptor getDescriptor(String name) {
        return plugins.get(name);
    }

    /** 按启用状态分组返回插件. */
    public PluginLoadResult getPlugins() {
        List<LoadedPlugin> enabled = new ArrayList<>();
        List<LoadedPlugin> disabled = new ArrayList<>();

        for (Map.Entry<String, PluginDescriptor> entry : plugins.entrySet()) {
            String name = entry.getKey();
            PluginDescriptor desc = entry.getValue();

            if (!desc.isAvailable()) {
                continue;
            }

            LoadedPlugin lp = toLoadedPlugin(desc);
            if (isEnabled(name)) {
                enabled.add(lp);
            } else {
                disabled.add(lp);
            }
        }

        return new PluginLoadResult(enabled, disabled);
    }

    /** 获取所有已启用插件的技能列表. */
    public List<Skill> getEnabledSkills() {
        List<Skill> result = new ArrayList<>();
        for (Map.Entry<String, PluginDescriptor> entry : plugins.entrySet()) {
            if (isEnabled(entry.getKey())) {
                result.addAll(entry.getValue().skills());
            }
        }
        return result;
    }

    /** 获取所有已启用插件的技能列表，按插件有序. */
    public Map<String, List<Skill>> getSkillsByPlugin() {
        Map<String, List<Skill>> result = new LinkedHashMap<>();
        for (Map.Entry<String, PluginDescriptor> entry : plugins.entrySet()) {
            if (isEnabled(entry.getKey())) {
                PluginDescriptor desc = entry.getValue();
                if (!desc.skills().isEmpty()) {
                    result.put(desc.name(), desc.skills());
                }
            }
        }
        return result;
    }

    /** 插件数量. */
    public int size() {
        return plugins.size();
    }

    // ==================== 内部 ====================

    private LoadedPlugin toLoadedPlugin(PluginDescriptor desc) {
        return new LoadedPlugin(
                desc.name(),
                desc.description(),
                desc.version(),
                desc.pluginId(),
                desc.skills(),
                isEnabled(desc.name()),
                true
        );
    }

    // ==================== 内部类型 ====================

    /** 插件加载结果，包含启用/禁用分组. */
    public record PluginLoadResult(List<LoadedPlugin> enabled, List<LoadedPlugin> disabled) {}

    /**
     * 已加载插件视图 — 对齐 claude-code 的 LoadedPlugin.
     */
    public record LoadedPlugin(
            String name,
            String description,
            String version,
            String pluginId,
            List<Skill> skills,
            boolean enabled,
            boolean isBuiltin
    ) {}
}
