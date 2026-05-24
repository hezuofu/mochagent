// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.plugin;

import io.sketch.mochaagents.agent.AgentLoop;
import io.sketch.mochaagents.evaluation.Evaluator;
import io.sketch.mochaagents.perception.Perceptor;
import io.sketch.mochaagents.plan.Planner;
import io.sketch.mochaagents.reasoning.Reasoner;
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Plugin loader — discovers plugins from directories and applies
 * their extension points to agents.
 *
 * <h2>Plugin directory structure</h2>
 * <pre>
 *   .mocha/plugins/
 *     custom-tools/
 *       plugin.json              ← descriptor
 *       lib/
 *         custom-tools.jar       ← classes (loaded via ServiceLoader or isolated classloader)
 *     enterprise-rules/
 *       plugin.json
 *       rules/
 *         security.policy        ← static resources
 * </pre>
 *
 * <h2>plugin.json format</h2>
 * <pre>{@code
 * {
 *   "name": "custom-tools",
 *   "version": "1.0",
 *   "description": "Custom tool collection",
 *   "extensions": [
 *     {"type": "TOOL", "class": "com.example.MyTool", "priority": 10},
 *     {"type": "PERCEPTOR", "class": "com.example.MyPerceptor", "priority": 5}
 *   ]
 * }
 * }</pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var loader = new PluginLoader(pluginsDir, toolRegistry);
 * loader.discoverAll();
 *
 * // Build agent with plugin-enhanced components
 * var config = MochaAgent.Config(...);
 * loader.applyExtensions(config);  // injects perceptor, reasoner, tools, etc.
 * }</pre>
 *
 * @author lanxia39@163.com
 */
public class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    private final List<Path> pluginDirs;
    private final ToolRegistry toolRegistry;
    private final Map<String, PluginDescriptor> discovered = new LinkedHashMap<>();

    public PluginLoader(Path pluginsDir, ToolRegistry toolRegistry) {
        this(List.of(pluginsDir), toolRegistry);
    }

    public PluginLoader(List<Path> pluginDirs, ToolRegistry toolRegistry) {
        this.pluginDirs = pluginDirs;
        this.toolRegistry = toolRegistry;
    }

    /** Discover all plugins from configured directories. */
    public void discoverAll() {
        for (Path dir : pluginDirs) {
            if (!Files.isDirectory(dir)) continue;
            try (var stream = Files.newDirectoryStream(dir)) {
                for (Path pluginDir : stream) {
                    if (!Files.isDirectory(pluginDir)) continue;
                    PluginDescriptor desc = discoverPlugin(pluginDir);
                    if (desc != null) {
                        discovered.put(desc.name(), desc);
                        applyPluginExtensions(desc);
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to scan plugin dir {}: {}", dir, e.getMessage());
            }
        }
        log.info("PluginLoader discovered {} plugins from {} dirs", discovered.size(), pluginDirs.size());
    }

    private PluginDescriptor discoverPlugin(Path pluginDir) {
        Path jsonFile = pluginDir.resolve("plugin.json");
        if (!Files.exists(jsonFile)) return null;

        try {
            String json = Files.readString(jsonFile);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(json);

            String name = node.has("name") ? node.get("name").asText() : pluginDir.getFileName().toString();
            String desc = node.has("description") ? node.get("description").asText() : "";
            String version = node.has("version") ? node.get("version").asText() : "0.1";
            boolean enabled = !node.has("enabled") || node.get("enabled").asBoolean();

            var extensions = new ArrayList<ExtensionPoint<?>>();
            if (node.has("extensions")) {
                for (var ext : node.get("extensions")) {
                    String type = ext.get("type").asText();
                    String className = ext.get("className").asText();
                    int priority = ext.has("priority") ? ext.get("priority").asInt() : 0;

                    loadExtension(type, className, priority, pluginDir).ifPresent(extensions::add);
                }
            }

            return PluginDescriptor.builder(name, desc)
                    .version(version)
                    .extensionPoints(extensions)
                    .defaultEnabled(enabled)
                    .build();

        } catch (Exception e) {
            log.warn("Failed to parse plugin.json in {}: {}", pluginDir, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<ExtensionPoint<?>> loadExtension(String type, String className,
                                                       int priority, Path pluginDir) {
        try {
            // Try loading the class from the plugin's lib directory
            Path libDir = pluginDir.resolve("lib");
            Class<?> clazz;
            if (Files.isDirectory(libDir)) {
                // TODO: isolated classloader for plugin JARs
                clazz = Class.forName(className);
            } else {
                clazz = Class.forName(className);
            }

            Object instance = clazz.getDeclaredConstructor().newInstance();

            return switch (type) {
                case "TOOL" -> instance instanceof Tool t
                        ? Optional.of(ExtensionPoint.tool(t, priority)) : Optional.empty();
                case "PERCEPTOR" -> instance instanceof Perceptor<?,?> p
                        ? Optional.of(ExtensionPoint.perceptor(p, priority)) : Optional.empty();
                case "REASONER" -> instance instanceof Reasoner r
                        ? Optional.of(ExtensionPoint.reasoner(r, priority)) : Optional.empty();
                case "PLANNER" -> instance instanceof Planner<?> p
                        ? Optional.of(ExtensionPoint.planner(p, priority)) : Optional.empty();
                case "EVALUATOR" -> instance instanceof Evaluator e
                        ? Optional.of(ExtensionPoint.evaluator(e, priority)) : Optional.empty();
                case "LOOP" -> instance instanceof AgentLoop<?,?> l
                        ? Optional.of(ExtensionPoint.loop(
                                (AgentLoop<String, String>) l, priority))
                        : Optional.empty();
                case "MCP_SERVER" -> instance instanceof String s
                        ? Optional.of(ExtensionPoint.mcpServer(s, priority)) : Optional.empty();
                default -> {
                    log.debug("Unknown extension type: {}", type);
                    yield Optional.empty();
                }
            };
        } catch (Exception e) {
            log.warn("Cannot load extension {} ({}) from {}: {}",
                    type, className, pluginDir, e.getMessage());
            return Optional.empty();
        }
    }

    /** Register tool/extension extensions immediately. */
    private void applyPluginExtensions(PluginDescriptor desc) {
        for (var ext : desc.extensionPoints()) {
            if ("TOOL".equals(ext.type()) && ext.component() instanceof Tool t) {
                toolRegistry.register(t);
                log.debug("Plugin '{}' registered tool: {}", desc.name(), t.getName());
            } else if ("MCP_SERVER".equals(ext.type()) && ext.component() instanceof String cmd) {
                try {
                    var mcp = new io.sketch.mochaagents.tool.mcp.StdioMcpClient();
                    mcp.connect(cmd);
                    if (mcp.isConnected()) {
                        for (var tool : mcp.discoverTools()) toolRegistry.register(tool);
                        log.info("Plugin '{}' MCP server connected: {}", desc.name(), cmd);
                    }
                } catch (Exception e) {
                    log.warn("Plugin '{}' MCP connect failed: {} → {}",
                            desc.name(), cmd, e.getMessage());
                }
            }
        }
    }

    /** Discovered plugins. */
    public Map<String, PluginDescriptor> plugins() { return Collections.unmodifiableMap(discovered); }

    // ── Dynamic loading ──

    /** Load a plugin from its class, activating it immediately. */
    public <T extends Plugin> T loadPlugin(Class<T> pluginClass) {
        try {
            T plugin = pluginClass.getDeclaredConstructor().newInstance();
            PluginDescriptor desc = PluginDescriptor.of(plugin.name(), plugin.version(), plugin.description());
            for (ExtensionPoint<?> ext : plugin.extensions()) {
                desc = desc.withExtension(ext);
            }
            discovered.put(desc.name(), desc);
            applyPluginExtensions(desc);
            plugin.onActivate();
            log.info("Plugin loaded: {} v{}", plugin.name(), plugin.version());
            return plugin;
        } catch (Exception e) {
            log.error("Failed to load plugin {}: {}", pluginClass.getName(), e.getMessage());
            return null;
        }
    }

    /** Load all Plugin implementations found via Java ServiceLoader. */
    public void loadFromServiceLoader() {
        for (Plugin plugin : java.util.ServiceLoader.load(Plugin.class)) {
            loadPlugin(plugin.getClass());
        }
    }
}
