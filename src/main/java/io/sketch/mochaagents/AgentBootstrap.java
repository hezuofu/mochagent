// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents;

import io.sketch.mochaagents.agent.loop.ToolCallingAgent;
import io.sketch.mochaagents.model.Model;
import io.sketch.mochaagents.tool.ToolRegistry;
import io.sketch.mochaagents.tool.internal.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Framework bootstrap — minimal init: creates ToolRegistry with base tools.
 *
 * <p>Skills, plugins, and MCP are loaded lazily via {@link #withSkills()},
 * {@link #withPlugins()}, {@link #withMcp()}.
 */
public final class AgentBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AgentBootstrap.class);

    private final ToolRegistry toolRegistry;
    private final Model model;

    private AgentBootstrap(Model model) {
        this.model = model;
        this.toolRegistry = new ToolRegistry();
        registerBaseTools();
        log.info("Bootstrapped: {} base tools", toolRegistry.size());
    }

    public static AgentBootstrap init() { return new AgentBootstrap(null); }
    public static AgentBootstrap init(Model model) { return new AgentBootstrap(model); }

    public ToolRegistry toolRegistry() { return toolRegistry; }
    public Model model() { return model; }

    /** Build a ToolCallingAgent with all registered tools. */
    public ToolCallingAgent buildAgent(String name) {
        return ToolCallingAgent.builder()
                .name(name).model(model).toolRegistry(toolRegistry).build();
    }

    // ── Backward-compat accessors ──

    public io.sketch.mochaagents.plugin.PluginBootstrap pluginBootstrap() {
        return io.sketch.mochaagents.plugin.PluginBootstrap.bootstrap(
                io.sketch.mochaagents.skill.SkillManager.bootstrap(toolRegistry).skillRegistry());
    }

    public io.sketch.mochaagents.tool.internal.AgentTool agentTool() { return null; }

    // ── Optional add-ons ──

    public AgentBootstrap withSkills() {
        io.sketch.mochaagents.skill.SkillManager.bootstrap(toolRegistry);
        return this;
    }

    public AgentBootstrap withPlugins() {
        io.sketch.mochaagents.plugin.PluginBootstrap.bootstrap(
                io.sketch.mochaagents.skill.SkillManager.bootstrap(toolRegistry).skillRegistry());
        return this;
    }

    public AgentBootstrap withMcp() {
        String servers = System.getenv("MCP_SERVERS");
        if (servers != null && !servers.isEmpty()) {
            var mcp = new io.sketch.mochaagents.tool.mcp.StdioMcpClient();
            for (String cmd : servers.split(",")) {
                cmd = cmd.trim();
                if (!cmd.isEmpty()) {
                    mcp.connect(cmd);
                    if (mcp.isConnected())
                        for (var t : mcp.discoverTools()) toolRegistry.register(t);
                }
            }
        }
        return this;
    }

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
        toolRegistry.register(new CodeExecutionTool());
    }
}
