package io.sketch.mochaagents.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * CLI bootstrap entrypoint — checks for special flags before loading the full CLI.
 * All imports are lazy (Class.forName / reflection) to minimize startup cost
 * for fast paths like {@code --version} and {@code --help}.
 *
 * <p>Aligns with {@code claude-code/src/entrypoints/cli.tsx main()}:
 * <ul>
 *   <li>Fast-path for --version/-v: zero class loading</li>
 *   <li>Fast-path for --help/-h: prints usage</li>
 *   <li>Flag pre-processing: --bare</li>
 *   <li>Default: loads full CLI via {@link CliMain#launch}</li>
 * </ul>
 *
 * <p>Feature flags (BRIDGE_MODE, DAEMON, BG_SESSIONS, TEMPLATES, etc.)
 * are intentionally excluded — these are Anthropic internal paths.
 * @author lanxia39@163.com
 */
public final class CliBootstrap {

    private static final Logger log = LoggerFactory.getLogger(CliBootstrap.class);
    private static final String VERSION = "0.1.0";

    private CliBootstrap() {
    }

    /**
     * Bootstrap entrypoint.
     *
     * @param args command-line arguments (argv[2:] equivalent)
     */
    public static void main(String[] args) {
        List<String> argList = Arrays.asList(args);
        log.debug("CLI bootstrap started, args={}", argList);

        // ── Fast-path: --version / -v ──
        if (args.length == 1 && ("--version".equals(args[0]) || "-v".equals(args[0]) || "-V".equals(args[0]))) {
            log.debug("Fast-path: version request");
            System.out.println(VERSION + " (MochaAgents)");
            return;
        }

        // ── Fast-path: --help / -h ──
        if (args.length == 0 || args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            log.debug("Fast-path: help request");
            printUsage();
            return;
        }

        // ── Flag pre-processing: --bare ──
        if (argList.contains("--bare")) {
            log.info("Bare mode enabled (mocha.simple=true)");
            System.setProperty("mocha.simple", "true");
        }

        // ── Bootstrap agent framework (skills + plugins) ──
        log.debug("Bootstrapping agent framework");
        AgentBootstrap.init();

        // ── Default: load full CLI ──
        log.info("Loading full CLI, command={}", args[0]);
        try {
            CliMain.launch(args);
        } catch (Exception e) {
            log.error("Failed to launch CLI", e);
            System.err.println("Error: Failed to launch CLI: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("MochaAgents " + VERSION + " — Java agentic coding framework");
        System.out.println();
        System.out.println("Usage: mocha <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  (default)       Start interactive REPL session");
        System.out.println("  mcp             Configure and manage MCP servers");
        System.out.println("  plugin          Manage plugins");
        System.out.println("  update          Check for updates");
        System.out.println("  doctor          Run diagnostic checks");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --version, -v   Print version");
        System.out.println("  --help, -h      Print this help");
        System.out.println("  --bare          Start in bare/simple mode");
        System.out.println("  --model <name>  Specify model (e.g., claude-sonnet-4-20250514)");
        System.out.println("  -p, --print     Print mode (non-interactive)");
        System.out.println("  --debug          Enable debug logging");
    }
}
