package io.sketch.mochaagents;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.plugin.PluginBootstrap;
import io.sketch.mochaagents.plugin.PluginLoader;
import io.sketch.mochaagents.skill.SkillManager;
import io.sketch.mochaagents.tool.ToolRegistry;
import io.sketch.mochaagents.tool.impl.AgentTool;
import io.sketch.mochaagents.tool.impl.BashTool;
import io.sketch.mochaagents.tool.impl.BugCheckTool;
import io.sketch.mochaagents.tool.impl.CalculatorTool;
import io.sketch.mochaagents.tool.impl.FileEditTool;
import io.sketch.mochaagents.tool.impl.FileReadTool;
import io.sketch.mochaagents.tool.impl.FileWriteTool;
import io.sketch.mochaagents.tool.impl.GlobTool;
import io.sketch.mochaagents.tool.impl.GrepTool;
import io.sketch.mochaagents.tool.impl.TodoWriteTool;
import io.sketch.mochaagents.tool.impl.WebFetchTool;
import io.sketch.mochaagents.tool.impl.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 框架启动引导 — 按正确顺序初始化 Skill → Plugin → AgentTool → PluginLoader → MCP 体系.
 *
 * <p>调用入口只需调用 {@link #init()} 或 {@link #init(LLM)} 即可获得完整注册表.
 *
 * <pre>{@code
 * var bootstrap = AgentBootstrap.init(llm);
 * var agent = MochaAgent.builder()
 *     .name("assistant").llm(llm)
 *     .toolRegistry(bootstrap.toolRegistry())
 *     .build();
 * // Apply plugin extensions to the agent config
 * bootstrap.pluginLoader().applyExtensions(agentConfig);
 * }</pre>
 * @author lanxia39@163.com
 */
public final class AgentBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AgentBootstrap.class);

    private final ToolRegistry toolRegistry;
    private final SkillManager skillManager;
    private final PluginBootstrap pluginBootstrap;
    private final AgentTool agentTool;
    private final PluginLoader pluginLoader;

    private AgentBootstrap(LLM llm) {
        this.toolRegistry = new ToolRegistry();
        this.skillManager = SkillManager.bootstrap(toolRegistry);
        this.pluginBootstrap = PluginBootstrap.bootstrap(skillManager.skillRegistry());

        // AgentTool — sub-agent spawning (claude-code pattern)
        this.agentTool = new AgentTool();
        if (llm != null) {
            this.agentTool.registerDefaultAgent(toolRegistry, llm);
        } else {
            this.agentTool.registerDefaultAgent(toolRegistry);
        }
        toolRegistry.register(agentTool);

        // P0: Register all built-in tools — now the agent has real filesystem + search capability
        registerBaseTools();

        // PluginLoader — discover plugins from standard dirs
        Path pluginsDir = Path.of(System.getProperty("user.home"), ".mocha", "plugins");
        this.pluginLoader = new PluginLoader(pluginsDir, toolRegistry);
        pluginLoader.discoverAll();

        // Auto-load MCP tools from MCP_SERVERS env var
        String mcpServers = System.getenv("MCP_SERVERS");
        if (mcpServers != null && !mcpServers.isEmpty()) {
            io.sketch.mochaagents.tool.mcp.StdioMcpClient mcp = new io.sketch.mochaagents.tool.mcp.StdioMcpClient();
            for (String cmd : mcpServers.split(",")) {
                cmd = cmd.trim();
                if (!cmd.isEmpty()) {
                    mcp.connect(cmd);
                    if (mcp.isConnected()) {
                        for (var tool : mcp.discoverTools()) toolRegistry.register(tool);
                    }
                }
            }
        }

        log.info("Agent framework bootstrapped: {} tools, {} skills, {} plugins, {} plugin-dirs",
                toolRegistry.size(), skillManager.skillRegistry().size(),
                pluginBootstrap.pluginManager().size(),
                pluginLoader.plugins().size());
    }

    /** Bootstrap without LLM — AgentTool falls back to noop. */
    public static AgentBootstrap init() { return new AgentBootstrap(null); }

    /** Bootstrap with LLM — AgentTool spawns real ToolCallingAgent sub-agents. */
    public static AgentBootstrap init(LLM llm) { return new AgentBootstrap(llm); }

    public ToolRegistry toolRegistry() { return toolRegistry; }
    public SkillManager skillManager() { return skillManager; }
    public PluginBootstrap pluginBootstrap() { return pluginBootstrap; }
    public AgentTool agentTool() { return agentTool; }
    public PluginLoader pluginLoader() { return pluginLoader; }

    /** Register all built-in tools — claude-code equivalent: getAllBaseTools(). */
    private void registerBaseTools() {
        toolRegistry.register(new BashTool());
        toolRegistry.register(new FileReadTool());
        toolRegistry.register(new FileWriteTool());
        toolRegistry.register(new FileEditTool());
        toolRegistry.register(new GlobTool());
        toolRegistry.register(new GrepTool());
        toolRegistry.register(new WebFetchTool());
        toolRegistry.register(new WebSearchTool());
        toolRegistry.register(new CalculatorTool());
        toolRegistry.register(new BugCheckTool());
        toolRegistry.register(new TodoWriteTool());
        log.info("Registered {} base tools", 12);
    }

    /** Health check — returns a status map suitable for monitoring endpoints. */
    public java.util.Map<String, Object> health() {
        java.util.Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("status", "UP");
        status.put("tools", toolRegistry.size());
        status.put("skills", skillManager.skillRegistry().size());
        status.put("plugins", pluginBootstrap.pluginManager().size());
        status.put("pluginEnabled", pluginBootstrap.pluginManager().getPlugins().enabled().size());
        return status;
    }
}
