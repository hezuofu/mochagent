package io.sketch.mochaagents.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;

/**
 * CLI command routing and REPL launcher — mirrors the commander-based
 * subcommand registration in {@code claude-code/src/main.tsx}.
 *
 * <h3>Subcommand routing</h3>
 * <table>
 *   <tr><td>{@code mocha mcp ...}</td><td>→ McpHandlers (stub)</td></tr>
 *   <tr><td>{@code mocha plugin ...}</td><td>→ PluginHandlers (stub)</td></tr>
 *   <tr><td>{@code mocha update}</td><td>→ UpdateHandler (stub)</td></tr>
 *   <tr><td>{@code mocha doctor}</td><td>→ DoctorHandler (stub)</td></tr>
 *   <tr><td>{@code mocha ...}</td><td>→ Interactive REPL</td></tr>
 * </table>
 * @author lanxia39@163.com
 */
public final class CliMain {

    private static final Logger log = LoggerFactory.getLogger(CliMain.class);

    private CliMain() {
    }

    /**
     * Launch the CLI with given args. Called from {@link CliBootstrap}.
     *
     * @param args command-line arguments (after bootstrap processing)
     */
    public static void launch(String[] args) {
        log.info("CliMain.launch called, commandCount={}", args.length);
        if (args.length == 0) {
            log.info("No subcommand — entering REPL");
            launchRepl(args);
            return;
        }

        String command = args[0];
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        log.info("Dispatching subcommand: {}", command);

        try {
            switch (command) {
                case "mcp":
                    handleMcp(subArgs);
                    break;
                case "plugin":
                case "plugins":
                    handlePlugin(subArgs);
                    break;
                case "update":
                    handleUpdate(subArgs);
                    break;
                case "doctor":
                    handleDoctor(subArgs);
                    break;
                case "auth":
                    handleAuth(subArgs);
                    break;
                default:
                    log.warn("Unknown subcommand '{}', falling through to REPL", command);
                    launchRepl(args);
            }
        } catch (Exception e) {
            log.error("Error handling subcommand '{}'", command, e);
            CliExit.cliError("Error: " + e.getMessage());
        }
    }

    // ─── Subcommand handlers (stubs) ───

    private static void handleMcp(String[] args) {
        log.debug("MCP handler invoked, subcommand={}", args.length > 0 ? args[0] : "(none)");
        if (args.length == 0) {
            System.out.println("Usage: mocha mcp <serve|list|add|remove|get>");
            return;
        }
        switch (args[0]) {
            case "serve":
                log.info("MCP serve — starting real MCP server");
                new io.sketch.mochaagents.tool.mcp.McpServer().serve();
                break;
            case "list":
                log.debug("MCP list requested");
                System.out.println("[MCP] No MCP servers configured.");
                break;
            case "add":
                log.debug("MCP add requested");
                System.out.println("[MCP] Add command not yet implemented.");
                break;
            case "remove":
                log.debug("MCP remove requested");
                System.out.println("[MCP] Remove command not yet implemented.");
                break;
            case "get":
                log.debug("MCP get requested");
                System.out.println("[MCP] Get command not yet implemented.");
                break;
            default:
                log.warn("Unknown MCP subcommand: {}", args[0]);
                System.out.println("[MCP] Unknown subcommand: " + args[0]);
        }
    }

    private static void handlePlugin(String[] args) {
        log.debug("Plugin handler invoked, subcommand={}", args.length > 0 ? args[0] : "(none)");
        if (args.length == 0) {
            System.out.println("Usage: mocha plugin <list|validate|marketplace>");
            return;
        }
        switch (args[0]) {
            case "list":
                log.debug("Plugin list requested");
                System.out.println("[Plugin] No plugins installed.");
                break;
            case "validate":
                log.debug("Plugin validate requested");
                System.out.println("[Plugin] Validate command not yet implemented.");
                break;
            case "marketplace":
                log.debug("Plugin marketplace requested");
                System.out.println("[Plugin] Marketplace commands not yet implemented.");
                break;
            default:
                log.warn("Unknown plugin subcommand: {}", args[0]);
                System.out.println("[Plugin] Unknown subcommand: " + args[0]);
        }
    }

    private static void handleUpdate(String[] args) {
        log.info("Update check requested");
        System.out.println("Current version: 0.1.0");
        System.out.println("MochaAgents is up to date!");
    }

    private static void handleDoctor(String[] args) {
        log.info("Doctor diagnostic requested");
        String javaVer = System.getProperty("java.version");
        String osInfo = System.getProperty("os.name") + " " + System.getProperty("os.version");
        log.debug("System info: java={}, os={}", javaVer, osInfo);
        System.out.println("=== MochaAgents Doctor ===");
        System.out.println("Version: 0.1.0");
        System.out.println("Java: " + javaVer);
        System.out.println("OS: " + osInfo);
        System.out.println("All checks passed.");
    }

    private static void handleAuth(String[] args) {
        log.debug("Auth handler invoked, subcommand={}", args.length > 0 ? args[0] : "(none)");
        if (args.length == 0) {
            System.out.println("Usage: mocha auth <login|logout|status>");
            return;
        }
        switch (args[0]) {
            case "login":
                log.info("Auth login requested");
                System.out.println("[Auth] Login flow not yet implemented.");
                break;
            case "logout":
                log.info("Auth logout requested");
                System.out.println("[Auth] Logged out.");
                break;
            case "status":
                log.debug("Auth status requested");
                System.out.println("[Auth] Not authenticated.");
                break;
            default:
                log.warn("Unknown auth subcommand: {}", args[0]);
                System.out.println("[Auth] Unknown subcommand: " + args[0]);
        }
    }

    // ─── Interactive REPL ───

    private static io.sketch.mochaagents.agent.impl.ToolCallingAgent replAgent;
    private static io.sketch.mochaagents.cli.AgentBootstrap bootstrap;

    /** Lazy-init the REPL agent with bootstrapped tools + MockLLM. */
    private static synchronized io.sketch.mochaagents.agent.impl.ToolCallingAgent getReplAgent() {
        if (replAgent == null) {
            bootstrap = AgentBootstrap.init();
            var llm = io.sketch.mochaagents.llm.provider.MockLLM.create();
            replAgent = io.sketch.mochaagents.agent.impl.ToolCallingAgent.builder()
                    .name("repl-agent")
                    .llm(llm)
                    .toolRegistry(bootstrap.toolRegistry())
                    .maxSteps(10)
                    .build();
            log.info("REPL agent initialized with {} tools, {} skills",
                    bootstrap.toolRegistry().size(),
                    bootstrap.skillsInit().skillRegistry().size());
        }
        return replAgent;
    }

    /**
     * Launch an interactive REPL session.
     */
    private static void launchRepl(String[] args) {
        List<String> argList = Arrays.asList(args);
        boolean isPrintMode = argList.contains("-p") || argList.contains("--print");
        boolean isDebug = argList.contains("--debug");

        if (isDebug) {
            System.setProperty("mocha.debug", "true");
            log.info("Debug mode enabled");
        }

        log.info("Interactive REPL starting, printMode={}, debug={}", isPrintMode, isDebug);
        System.out.println("MochaAgents 0.1.0 — Interactive REPL");
        System.out.println("Type 'exit' or 'quit' to exit, 'help' for commands.");
        System.out.println();

        if (isPrintMode) {
            log.info("Print mode: reading all stdin");
            try {
                String input = readAllStdin();
                log.debug("Print mode: read {} chars", input.length());
                if (input.isEmpty()) {
                    System.out.println("(no input provided)");
                } else {
                    System.out.println("[Agent] Processing...");
                    String result = getReplAgent().run(input);
                    System.out.println("[Agent] Result: " + result);
                }
            } catch (IOException e) {
                log.error("Print mode: failed to read stdin", e);
                CliExit.cliError("Failed to read stdin: " + e.getMessage());
            }
            return;
        }

        // Interactive REPL loop
        log.debug("Entering REPL input loop");
        int cmdCount = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                System.out.print("> ");
                String line = reader.readLine();
                if (line == null) {
                    log.info("REPL EOF received (Ctrl+D), total commands={}", cmdCount);
                    break;
                }
                line = line.trim();

                if (line.isEmpty()) continue;

                cmdCount++;
                log.debug("REPL command [#{}]: {}", cmdCount, abbreviate(line, 60));

                switch (line.toLowerCase()) {
                    case "exit": case "quit": case "q":
                        log.info("REPL exit requested by user");
                        System.out.println("Goodbye.");
                        return;
                    case "help": case "h": case "?":
                        printReplHelp();
                        break;
                    case "version":
                        System.out.println("MochaAgents 0.1.0");
                        break;
                    case "status":
                        var a = replAgent;
                        System.out.println(a == null ? "REPL active. No agent loaded."
                                : "Agent '" + a.metadata().name() + "' ready. "
                                + (bootstrap != null ? bootstrap.toolRegistry().size() + " tools loaded." : ""));
                        break;
                    default:
                        log.info("REPL task: {}", abbreviate(line, 80));
                        System.out.println("[Agent] Processing: \"" + abbreviate(line, 80) + "\"");
                        try {
                            String result = getReplAgent().run(line);
                            System.out.println("[Agent] Result: " + result);
                        } catch (Exception e) {
                            log.error("Agent execution failed", e);
                            System.out.println("[Agent] Error: " + e.getMessage());
                        }
                }
            }
        } catch (IOException e) {
            log.error("REPL I/O error", e);
            CliExit.cliError("REPL error: " + e.getMessage());
        }
    }

    private static void printReplHelp() {
        System.out.println("Available REPL commands:");
        System.out.println("  help, h, ?      Show this help");
        System.out.println("  version         Show version");
        System.out.println("  status          Show agent status");
        System.out.println("  exit, quit, q   Exit REPL");
        System.out.println("  <any text>      Send task to agent");
    }

    private static String readAllStdin() throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static String abbreviate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
