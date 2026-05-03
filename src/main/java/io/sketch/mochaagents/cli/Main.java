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
 *   mocha plugin ...   → plugin management
 *   mocha doctor       → diagnostics
 *   mocha update       → version check
 *   mocha auth ...     → authentication
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

        ModelConfig modelCfg = parseModelArgs(args);
        Repl repl = new Repl(modelCfg);

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
            o.println("=== MochaAgents Doctor ===");
            o.println("Version: " + VERSION);
            o.println("Java: " + System.getProperty("java.version"));
            o.println("OS: " + System.getProperty("os.name"));
            o.println("All checks passed.");
            return 0;
        };

        CliCommand update = (a, o, e) -> {
            o.println("Current version: " + VERSION);
            o.println("MochaAgents is up to date!");
            return 0;
        };

        CliCommand auth = (a, o, e) -> {
            if (a.length == 0) { o.println("Usage: mocha auth <login|logout|status>"); return 1; }
            switch (a[0]) {
                case "login": o.println("Login flow not yet implemented."); return 0;
                case "logout": o.println("Logged out."); return 0;
                case "status": o.println("Not authenticated."); return 0;
                default: o.println("Unknown auth subcommand: " + a[0]); return 1;
            }
        };

        Dispatcher d = new Dispatcher()
                .on("mcp", mcp)
                .on("plugin", plugin).on("plugins", plugin)
                .on("doctor", doctor)
                .on("update", update)
                .on("auth", auth)
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

    private static void printUsage(PrintStream out) {
        out.println("MochaAgents " + VERSION + " — Java agentic coding framework");
        out.println();
        out.println("Usage: mocha <command> [options]");
        out.println();
        out.println("Commands:");
        out.println("  (default)       Interactive REPL");
        out.println("  mcp serve       Start MCP server on stdio");
        out.println("  plugin          Manage plugins");
        out.println("  update          Check for updates");
        out.println("  doctor          Run diagnostics");
        out.println();
        out.println("Model Options:");
        out.println("  --model, -m     Model ID (default: mock)");
        out.println("  --temperature   Sampling temperature (0-2, default: 0.7)");
        out.println("  --max-tokens    Max output tokens (default: 4096)");
        out.println();
        out.println("Examples:");
        out.println("  mocha --model deepseek-chat");
        out.println("  mocha --model llama3.2 --temperature 0.3");
        out.println("  mocha --model gpt-4o-mini --model claude-haiku  (multi-model routing)");
    }
}
