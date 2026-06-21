// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents;

import io.sketch.mochaagents.agent.MochaAgent;
import io.sketch.mochaagents.model.Model;
import io.sketch.mochaagents.tool.ToolRegistry;
import io.sketch.mochaagents.tool.builtin.*;

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

    /** Build a fully-featured MochaAgent with all plugin extensions applied. */
    public MochaAgent buildAgent(String name) {
        var builder = MochaAgent.builder(name, model)
                .toolRegistry(toolRegistry)
                .globalMemory(true)
                .permissionRules(permissionRules)
                .decisionPipeline(decisionPipeline)
                .approvalBroker(approvalBroker);
        applyPluginExtensions(builder);
        return builder.build();
    }

    /** Apply extension points from all enabled plugins to the agent builder. */
    private void applyPluginExtensions(MochaAgent.Builder builder) {
        if (pluginBootstrap == null) {
            return;
        }
        var plugins = pluginBootstrap.pluginManager().getPlugins().enabled();
        for (var plugin : plugins) {
            var desc = pluginBootstrap.pluginManager().getDescriptor(plugin.name());
            if (desc == null) {
                continue;
            }
            for (var ext : desc.extensionPoints()) {
                switch (ext.type()) {
                    case "TOOL" -> {
                        if (ext.component() instanceof io.sketch.mochaagents.tool.Tool t) {
                            toolRegistry.register(t);
                            log.debug("Plugin '{}' registered tool: {}", plugin.name(), t.getName());
                        }
                    }
                    case "LOOP" -> {
                        @SuppressWarnings("unchecked")
                        var l = (io.sketch.mochaagents.agent.AgentLoop<String, String>) ext.component();
                        builder.loop(l);
                        log.info("Plugin '{}' set agent loop: {}", plugin.name(), l.getClass().getSimpleName());
                    }
                    case "MEMORY" -> {
                        if (ext.component() instanceof io.sketch.mochaagents.memory.MemoryPlugin m) {
                            builder.memoryPlugin(m);
                            log.info("Plugin '{}' registered memory plugin: {}", plugin.name(), m.name());
                        }
                    }
                    case "PERCEPTOR" -> {
                        if (ext.component() instanceof io.sketch.mochaagents.perception.Perceptor<?, ?> p) {
                            @SuppressWarnings("unchecked")
                            var typed = (io.sketch.mochaagents.perception.Perceptor<String, String>) p;
                            builder.withPerception(typed);
                            log.debug("Plugin '{}' set perceptor: {}", plugin.name(), p.getClass().getSimpleName());
                        }
                    }
                    case "REASONER" -> {
                        if (ext.component() instanceof io.sketch.mochaagents.reasoning.Reasoner r) {
                            builder.withReasoning(r);
                            log.debug("Plugin '{}' set reasoner: {}", plugin.name(), r.getClass().getSimpleName());
                        }
                    }
                    case "PLANNER" -> {
                        if (ext.component() instanceof io.sketch.mochaagents.plan.Planner<?> p) {
                            @SuppressWarnings("unchecked")
                            var typed = (io.sketch.mochaagents.plan.Planner<String>) p;
                            builder.withPlanning(typed);
                            log.debug("Plugin '{}' set planner: {}", plugin.name(), p.getClass().getSimpleName());
                        }
                    }
                    case "EVALUATOR" -> {
                        if (ext.component() instanceof io.sketch.mochaagents.evaluation.Evaluator e) {
                            builder.withEvaluation(e);
                            log.debug("Plugin '{}' set evaluator: {}", plugin.name(), e.getClass().getSimpleName());
                        }
                    }
                    case "MCP_SERVER" -> {
                        if (ext.component() instanceof String cmd) {
                            if (mcpClient == null) {
                                mcpClient = new io.sketch.mochaagents.tool.mcp.StdioMcpClient();
                            }
                            mcpClient.connect(cmd);
                            if (mcpClient.isConnected()) {
                                for (var t : mcpClient.discoverTools()) {
                                    toolRegistry.register(t);
                                }
                                log.info("Plugin '{}' connected MCP: {}", plugin.name(), cmd);
                            }
                        }
                    }
                }
            }
        }
    }

    private io.sketch.mochaagents.plugin.PluginBootstrap pluginBootstrap;

    /** One-liner: create agent and run a task. */
    public String run(String task) {
        return buildAgent("mocha").run(task);
    }

    public io.sketch.mochaagents.plugin.PluginBootstrap pluginBootstrap() {
        if (pluginBootstrap == null) {
            pluginBootstrap = io.sketch.mochaagents.plugin.PluginBootstrap.bootstrap(
                    io.sketch.mochaagents.skill.SkillManager.bootstrap(toolRegistry).skillRegistry());
        }
        return pluginBootstrap;
    }

    // ── Optional add-ons ──

    public AgentBootstrap withSkills() {
        io.sketch.mochaagents.skill.SkillManager.bootstrap(toolRegistry);
        return this;
    }

    public AgentBootstrap withPlugins() {
        withSkills(); // Skills must be loaded first
        pluginBootstrap = io.sketch.mochaagents.plugin.PluginBootstrap.bootstrap(
                io.sketch.mochaagents.skill.SkillManager.bootstrap(toolRegistry).skillRegistry());
        return this;
    }

    private io.sketch.mochaagents.tool.mcp.StdioMcpClient mcpClient;

    public AgentBootstrap withMcp() {
        String servers = System.getenv("MCP_SERVERS");
        if (servers != null && !servers.isEmpty()) {
            mcpClient = new io.sketch.mochaagents.tool.mcp.StdioMcpClient();
            for (String cmd : servers.split(",")) {
                cmd = cmd.trim();
                if (!cmd.isEmpty()) {
                    mcpClient.connect(cmd);
                    if (mcpClient.isConnected()) {
                        for (var t : mcpClient.discoverTools()) {
                            toolRegistry.register(t);
                        }
                    }
                }
            }
        }
        return this;
    }

    /** Register LSP for one extension. */
    public AgentBootstrap withLsp(String extension, String serverName, String command, String... args) {
        io.sketch.mochaagents.lsp.LspManager lsp = getOrCreateLsp();
        lsp.register(serverName, extension,
                new io.sketch.mochaagents.lsp.LspServer.LspServerConfig(command, args));
        toolRegistry.register(new LspTool(lsp));

        var diag = new io.sketch.mochaagents.lsp.LspDiagnostics();
        io.sketch.mochaagents.lsp.LspDiagnosticBridge.install(lsp, diag);
        log.info("LSP diagnostics enabled for .{}", extension);
        return this;
    }

    /** Register TypeScript LSP. */
    public AgentBootstrap withTypeScriptLsp() {
        return withLsp("ts", "typescript", "typescript-language-server", "--stdio")
               .withLsp("tsx", "typescript", "typescript-language-server", "--stdio");
    }

    /** Register Python LSP. */
    public AgentBootstrap withPythonLsp() {
        return withLsp("py", "python", "pyright-langserver", "--stdio");
    }

    // ── Interaction ──

    private io.sketch.mochaagents.interaction.PermissionRules permissionRules;
    private io.sketch.mochaagents.interaction.ApprovalBroker approvalBroker;
    private io.sketch.mochaagents.interaction.DecisionPipeline decisionPipeline;

    public AgentBootstrap withPermissions(io.sketch.mochaagents.interaction.InteractionMode mode) {
        permissionRules = new io.sketch.mochaagents.interaction.PermissionRules()
                .defaultBehavior(mode == io.sketch.mochaagents.interaction.InteractionMode.AUTONOMOUS
                        ? io.sketch.mochaagents.interaction.PermissionRules.Behavior.ALLOW
                        : io.sketch.mochaagents.interaction.PermissionRules.Behavior.ASK);
        return this;
    }

    public AgentBootstrap withApprovalHandler(io.sketch.mochaagents.interaction.ApprovalBroker.Handler handler) {
        if (approvalBroker == null) {
            approvalBroker = new io.sketch.mochaagents.interaction.ApprovalBroker();
        }
        decisionPipeline = io.sketch.mochaagents.interaction.DecisionPipeline.standard();
        approvalBroker.register(handler);
        return this;
    }

    public io.sketch.mochaagents.interaction.ApprovalBroker broker() { return approvalBroker; }
    public io.sketch.mochaagents.interaction.PermissionRules permissionRules() { return permissionRules; }

    // ── LSP ──

    private io.sketch.mochaagents.lsp.LspManager lspManager;

    private io.sketch.mochaagents.lsp.LspManager getOrCreateLsp() {
        if (lspManager == null) {
            lspManager = new io.sketch.mochaagents.lsp.LspManager();
        }
        return lspManager;
    }

    public io.sketch.mochaagents.lsp.LspManager lspManager() { return lspManager; }

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
        toolRegistry.register(new FormatTool());
    }
}
