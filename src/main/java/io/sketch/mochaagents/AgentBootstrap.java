package io.sketch.mochaagents;

import io.sketch.mochaagents.plugin.PluginBootstrap;
import io.sketch.mochaagents.skill.SkillManager;
import io.sketch.mochaagents.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 框架启动引导 — 按正确顺序初始化 Skill → Plugin → Tool 体系.
 *
 * <p>调用入口（CLI / Server / Test）只需调用 {@link #init()} 即可获得完整注册表.
 * @author lanxia39@163.com
 */
public final class AgentBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AgentBootstrap.class);

    private final ToolRegistry toolRegistry;
    private final SkillManager skillManager;
    private final PluginBootstrap pluginBootstrap;

    private AgentBootstrap() {
        this.toolRegistry = new ToolRegistry();
        this.skillManager = SkillManager.bootstrap(toolRegistry);
        this.pluginBootstrap = PluginBootstrap.bootstrap(skillManager.skillRegistry());
        log.info("Agent framework bootstrapped: {} tools, {} skills, {} plugins",
                toolRegistry.size(), skillManager.skillRegistry().size(),
                pluginBootstrap.pluginManager().size());
    }

    /** 引导整个 Agent 框架，返回统一入口. */
    public static AgentBootstrap init() {
        return new AgentBootstrap();
    }

    public ToolRegistry toolRegistry() { return toolRegistry; }
    public SkillManager skillManager() { return skillManager; }
    public PluginBootstrap pluginBootstrap() { return pluginBootstrap; }
}
