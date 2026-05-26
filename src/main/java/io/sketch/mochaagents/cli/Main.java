// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.cli;

import io.sketch.mochaagents.AgentBootstrap;
import io.sketch.mochaagents.tool.mcp.McpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;

/**
 * CLI entry point — mirrors the claude-code commander-based subcommand routing.
 *
 * <pre>
 *   mocha mcp serve    → MCP server on stdio
 *   mocha plugin list  → list loaded plugins
 *   mocha doctor       → diagnostics
 *   mocha              → interactive REPL
 * </pre>
 * @author lanxia39@163.com
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final String VERSION = "0.1.0";

    private Main() {}

    public static void main(String[] args) {
        int code = launch(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }

    public static int launch(String[] args) { return launch(args, System.out, System.err); }

    public static int launch(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1 && ("--version".equals(args[0]) || "-v".equals(args[0]))) {
            out.println(VERSION + " (MochaAgents)"); return 0;
        }
        if (args.length == 0 || args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            printUsage(out); return 0;
        }

        // ── Session listing ──
        if (args.length == 1 && "--list".equals(args[0])) {
            printSessionList(out); return 0;
        }

        // ── Session resume flags ──
        String resumeSessionId = null;
        for (int i = 0; i < args.length; i++) {
            if ("--resume".equals(args[i]) && i + 1 < args.length) {
                resumeSessionId = args[++i];
            } else if ("--continue".equals(args[i])) {
                resumeSessionId = "latest";
            }
        }

        ModelConfig modelCfg = parseModelArgs(args);
        Repl repl = resumeSessionId != null
                ? new Repl(modelCfg, resumeSessionId)
                : new Repl(modelCfg);

        CliCommand mcp = (a, o, e) -> {
            if (a.length == 0) { o.println("Usage: mocha mcp <serve>"); return 1; }
            if ("serve".equals(a[0])) { new McpServer().serve(); return 0; }
            o.println("Unknown MCP subcommand: " + a[0]); return 1;
        };

        CliCommand plugin = (a, o, e) -> {
            if (a.length == 0) { o.println("Usage: mocha plugin <list>"); return 1; }
            if ("list".equals(a[0])) {
                var bootstrap = AgentBootstrap.init();
                var plugins = bootstrap.pluginBootstrap().pluginManager().getPlugins();
                o.println("Enabled plugins: " + plugins.enabled().size());
                plugins.enabled().forEach(p -> o.println("  - " + p.name()
                        + " v" + p.version() + " (" + p.skills().size() + " skills)"));
                if (plugins.enabled().isEmpty()) o.println("  (none)");
                return 0;
            }
            o.println("Unknown plugin subcommand: " + a[0]); return 1;
        };

        CliCommand doctor = (a, o, e) -> {
            o.println("=== MochaAgents v" + VERSION + " ===");
            o.println("Java:    " + System.getProperty("java.version")
                    + " | OS: " + System.getProperty("os.name"));
            o.println("Cores:   " + Runtime.getRuntime().availableProcessors()
                    + " | Mem: " + (Runtime.getRuntime().maxMemory() >> 20) + "MB");
            var bootstrap = AgentBootstrap.init();
            o.println("Tools:   " + bootstrap.toolRegistry().size());
            o.println("Skills:  " + bootstrap.pluginBootstrap().pluginManager().size());
            o.println("Status:  OK");
            return 0;
        };

        Dispatcher d = new Dispatcher()
                .on("mcp", mcp)
                .on("plugin", plugin).on("plugins", plugin)
                .on("doctor", doctor)
                .otherwise(repl);

        log.info("Dispatching: {}", args[0]);
        return d.dispatch(args, out, err);
    }

    private static ModelConfig parseModelArgs(String[] args) {
        ModelConfig cfg = new ModelConfig();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--model": case "-m":
                    if (i + 1 < args.length) cfg.model(args[++i], null); break;
                case "--temperature": case "-t":
                    if (i + 1 < args.length) cfg.temperature(Double.parseDouble(args[++i])); break;
                case "--max-tokens": case "-M":
                    if (i + 1 < args.length) cfg.maxTokens(Integer.parseInt(args[++i])); break;
                case "--debug": cfg.debug(true); break;
            }
        }
        return cfg;
    }

    private static void printSessionList(PrintStream out) {
        var mem = io.sketch.mochaagents.memory.MemoryManager.create();
        String cwd = System.getProperty("user.dir", ".");
        var sessions = mem.listSessions(cwd);
        if (sessions.isEmpty()) {
            out.println("No sessions found in current project.");
            return;
        }
        out.println("Recent sessions:");
        int count = 0;
        for (var s : sessions) {
            if (count++ >= 10) break;
            String title = s.title() != null ? s.title() : "(untitled)";
            out.printf("  %s  %s  %s  %d msgs%n",
                    s.id().substring(0, 8),
                    s.startedAt().toString().substring(0, 16).replace("T", " "),
                    title,
                    s.messageCount());
        }
        out.println();
        out.println("Resume: mocha --resume <id>    or    mocha --continue");
    }

    private static void printUsage(PrintStream out) {
        out.println("MochaAgents " + VERSION + " — Java agentic coding framework");
        out.println();
        out.println("Usage: mocha <command> [options]");
        out.println();
        out.println("Commands:");
        out.println("  (default)       Interactive REPL");
        out.println("  mcp serve       Start MCP server on stdio");
        out.println("  plugin list     List loaded plugins");
        out.println("  doctor          Run diagnostics");
        out.println();
        out.println("Session Options:");
        out.println("  --list          List recent sessions");
        out.println("  --resume <id>   Resume a specific session");
        out.println("  --continue      Resume the latest session");
        out.println();
        out.println("Model Options:");
        out.println("  --model, -m     Model ID (default: mock)");
        out.println("  --temperature   Sampling temperature (0-2, default: 0.7)");
        out.println("  --max-tokens    Max output tokens (default: 4096)");
        out.println();
        out.println("Examples:");
        out.println("  mocha --model deepseek-chat");
        out.println("  mocha --model llama3.2 --temperature 0.3");
        out.println("  mocha --continue --model gpt-4o-mini");
        out.println("  mocha --list");
    }
}
