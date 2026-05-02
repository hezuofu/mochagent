package io.sketch.mochaagents.plugin;

import io.sketch.mochaagents.skill.Skill;
import io.sketch.mochaagents.skill.SkillRegistry;
import io.sketch.mochaagents.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 插件系统初始化入口 — 对齐 claude-code 的 initBuiltinPlugins().
 *
 * <p>在框架启动时调用，完成:
 * <ol>
 *   <li>创建 PluginManager</li>
 *   <li>注册内置插件</li>
 *   <li>将已启用插件的技能注册到 SkillRegistry</li>
 * </ol>
 *
 * <p>典型用法:
 * <pre>{@code
 * PluginBootstrap pluginBootstrap = PluginBootstrap.bootstrap(skillRegistry);
 * // 后续可通过 pluginBootstrap.pluginManager() 管理插件
 * }</pre>
 */
public class PluginBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PluginBootstrap.class);

    private final PluginManager pluginManager;
    private final SkillRegistry skillRegistry;

    private PluginBootstrap(SkillRegistry skillRegistry) {
        this.pluginManager = new PluginManager();
        this.skillRegistry = skillRegistry;
    }

    /**
     * 引导插件系统.
     *
     * @param skillRegistry 全局技能注册表
     * @return 初始化后的 PluginBootstrap 实例
     */
    public static PluginBootstrap bootstrap(SkillRegistry skillRegistry) {
        PluginBootstrap bootstrap = new PluginBootstrap(skillRegistry);
        bootstrap.registerBuiltinPlugins();
        bootstrap.syncSkillsToRegistry();
        log.info("Plugin system initialized: {} plugins ({} enabled)",
                bootstrap.pluginManager.size(),
                bootstrap.pluginManager.getPlugins().enabled().size());
        return bootstrap;
    }

    /** 获取插件管理器. */
    public PluginManager pluginManager() {
        return pluginManager;
    }

    // ==================== 内置插件注册 ====================

    /**
     * 注册所有内置插件.
     * 新增内置插件在此方法中添加。
     */
    private void registerBuiltinPlugins() {
        // 预留：后续可添加内置插件
        // pluginManager.register(PluginDescriptor.builder("code-review", "Code review tools")
        //     .skills(List.of(reviewPrSkill, commitSkill))
        //     .build());

        log.debug("Builtin plugins registered");
    }

    // ==================== 技能同步 ====================

    /**
     * 将已启用插件的技能同步到 SkillRegistry.
     * 每次插件启用/禁用后应调用此方法。
     */
    public void syncSkillsToRegistry() {
        // Remove all plugin-sourced skills
        pluginManager.getPlugins().disabled().forEach(lp -> {
            for (Skill skill : lp.skills()) {
                skillRegistry.unregister(skill.name());
            }
        });

        // Register enabled plugin skills
        List<Skill> enabledSkills = pluginManager.getEnabledSkills();
        skillRegistry.registerAll(enabledSkills);

        log.debug("Synced {} plugin skills to registry", enabledSkills.size());
    }

    /**
     * 启用插件并同步技能.
     */
    public void enablePlugin(String name) {
        pluginManager.enable(name);
        syncSkillsToRegistry();
    }

    /**
     * 禁用插件并同步技能.
     */
    public void disablePlugin(String name) {
        pluginManager.disable(name);
        syncSkillsToRegistry();
    }
}
