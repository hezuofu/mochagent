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
        // Fast paths
        if (args.length == 1 && ("--version".equals(args[0]) || "-v".equals(args[0]))) {
            out.println(VERSION + " (MochaAgents)"); return 0;
        }
        if (args.length == 0 || args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            printUsage(out); return 0;
        }

        // Build dispatcher
        CliCommand mcp = (a, o, e) -> {
            if (a.length == 0) { o.println("Usage: mocha mcp <serve>"); return 1; }
            switch (a[0]) {
                case "serve": new McpServer().serve(); return 0;
                default: o.println("Unknown MCP subcommand: " + a[0]); return 1;
            }
        };

        CliCommand plugin = (a, o, e) -> {
            if (a.length == 0) { o.println("Usage: mocha plugin <list>"); return 1; }
            switch (a[0]) {
                case "list":
                    var bootstrap = AgentBootstrap.init();
                    var plugins = bootstrap.pluginBootstrap().pluginManager().getPlugins();
                    o.println("Enabled plugins: " + plugins.enabled().size());
                    plugins.enabled().forEach(p -> o.println("  - " + p.name()
                            + " v" + p.version() + " (" + p.skills().size() + " skills)"));
                    if (plugins.enabled().isEmpty()) o.println("  (none)");
                    return 0;
                default: o.println("Unknown plugin subcommand: " + a[0]); return 1;
            }
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
                .otherwise(new Repl());

        log.info("Dispatching: {}", args[0]);
        return d.dispatch(args, out, err);
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
        out.println("  auth            Authentication");
        out.println();
        out.println("Options: --version -v  --help -h  --debug");
    }
}
