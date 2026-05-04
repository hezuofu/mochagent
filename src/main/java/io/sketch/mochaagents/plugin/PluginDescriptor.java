package io.sketch.mochaagents.plugin;

import io.sketch.mochaagents.skill.Skill;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 插件描述符 — 对齐 claude-code 的 BuiltinPluginDefinition.
 *
 * <p>描述一个内置插件的元数据和组件清单。插件可以包含:
 * <ul>
 *   <li>Skills — 技能列表，注册到 SkillRegistry</li>
 *   <li>MCP Servers — MCP 服务配置（预留）</li>
 *   <li>Hooks — 钩子配置（预留）</li>
 * </ul>
 *
 * <p>插件 ID 格式: {@code name@builtin}，与 marketplace 插件 {@code name@marketplace} 区分。
 * @author lanxia39@163.com
 */
public final class PluginDescriptor {

    private final String name;
    private final String description;
    private final String version;
    private final List<Skill> skills;
    private final List<ExtensionPoint<?>> extensionPoints;
    private final boolean defaultEnabled;
    private final Supplier<Boolean> isAvailable;

    private PluginDescriptor(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name must not be null");
        this.description = Objects.requireNonNull(builder.description, "description must not be null");
        this.version = builder.version;
        this.skills = List.copyOf(builder.skills);
        this.extensionPoints = List.copyOf(builder.extensionPoints);
        this.defaultEnabled = builder.defaultEnabled;
        this.isAvailable = builder.isAvailable;
    }

    // ==================== 访问器 ====================

    public String name() { return name; }
    public String description() { return description; }
    public String version() { return version; }
    public List<Skill> skills() { return skills; }
    public List<ExtensionPoint<?>> extensionPoints() { return extensionPoints; }
    public boolean defaultEnabled() { return defaultEnabled; }

    /** 插件 ID（name@builtin 格式）. */
    public String pluginId() {
        return name + "@builtin";
    }

    /** 检查插件是否在当前环境中可用. */
    public boolean isAvailable() {
        return isAvailable != null ? isAvailable.get() : true;
    }

    // ==================== Builder ====================

    public static Builder builder(String name, String description) {
        return new Builder(name, description);
    }

    public static final class Builder {
        private final String name;
        private final String description;
        private String version = "";
        private List<Skill> skills = Collections.emptyList();
        private List<ExtensionPoint<?>> extensionPoints = Collections.emptyList();
        private boolean defaultEnabled = true;
        private Supplier<Boolean> isAvailable = () -> true;

        Builder(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public Builder version(String v) { this.version = v; return this; }
        public Builder skills(List<Skill> v) { this.skills = v; return this; }
        public Builder extensionPoints(List<ExtensionPoint<?>> v) { this.extensionPoints = v; return this; }
        public Builder defaultEnabled(boolean v) { this.defaultEnabled = v; return this; }
        public Builder isAvailable(Supplier<Boolean> v) { this.isAvailable = v; return this; }

        public PluginDescriptor build() {
            return new PluginDescriptor(this);
        }
    }
}
