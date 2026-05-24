// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents;

import io.sketch.mochaagents.agent.MochaAgent;
import io.sketch.mochaagents.model.Model;
import io.sketch.mochaagents.tool.ToolRegistry;
import io.sketch.mochaagents.tool.internal.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Framework bootstrap — creates a fully-featured MochaAgent with base tools.
 *
 * <pre>{@code
 * var agent = AgentBootstrap.init(model).buildAgent("assistant");
 * String result = agent.run("What is 2+2?");
 * }</pre>
 *
 * @author lanxia39@163.com
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

    /** Build a fully-featured MochaAgent with learning + global memory. */
    public MochaAgent buildAgent(String name) {
        return MochaAgent.builder(name, model)
                .toolRegistry(toolRegistry)
                .globalMemory(true)
                .build();
    }

    /** @deprecated sub-agents are spawned via AgentTool registered in ToolRegistry */
    @Deprecated
    public io.sketch.mochaagents.tool.internal.AgentTool agentTool() { return null; }

    public io.sketch.mochaagents.plugin.PluginBootstrap pluginBootstrap() {
        return io.sketch.mochaagents.plugin.PluginBootstrap.bootstrap(
                io.sketch.mochaagents.skill.SkillManager.bootstrap(toolRegistry).skillRegistry());
    }

    // ── Optional add-ons ──

    public AgentBootstrap withSkills() {
        io.sketch.mochaagents.skill.SkillManager.bootstrap(toolRegistry);
        return this;
    }

    public AgentBootstrap withPlugins() {
        withSkills(); // Skills must be loaded first
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
