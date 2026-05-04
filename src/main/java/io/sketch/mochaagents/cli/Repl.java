package io.sketch.mochaagents.cli;

import io.sketch.mochaagents.AgentBootstrap;
import io.sketch.mochaagents.agent.impl.ToolCallingAgent;
import io.sketch.mochaagents.agent.react.PlanMode;
import io.sketch.mochaagents.llm.LLM;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;

/**
 * Interactive REPL — claude-code style startup, streaming execution, diff display.
 *
 * <h2>Startup screen</h2>
 * <pre>
 *   ███╗   ███╗ ██████╗  ██████╗██╗  ██╗ █████╗
 *   ████╗ ████║██╔═══██╗██╔════╝██║  ██║██╔══██╗
 *   ██╔████╔██║██║   ██║██║     ███████║███████║
 *   ██║╚██╔╝██║██║   ██║██║     ██╔══██║██╔══██║
 *   ██║ ╚═╝ ██║╚██████╔╝╚██████╗██║  ██║██║  ██║
 *   ╚═╝     ╚═╝ ╚═════╝  ╚═════╝╚═╝  ╚═╝╚═╝  ╚═╝
 *   MochaAgent v0.1.0 — Java agentic coding framework
 * </pre>
 *
 * <h2>Commands</h2>
 * <pre>
 *   /help          show commands
 *   /model         show model info
 *   /cost          show session cost
 *   /clear         clear conversation
 *   /compact       compact context
 *   /plan          enter plan mode
 *   /exitplan      exit plan mode
 *   /exit, /quit   exit REPL
 *   /diff [file]   show pending file changes
 * </pre>
 *
 * @author lanxia39@163.com
 */
final class Repl implements CliCommand {

    private static final Logger log = LoggerFactory.getLogger(Repl.class);
    private static final String VERSION = "0.1.0";
    private static final String BANNER = """
            ███╗   ███╗ ██████╗  ██████╗██╗  ██╗ █████╗
            ████╗ ████║██╔═══██╗██╔════╝██║  ██║██╔══██╗
            ██╔████╔██║██║   ██║██║     ███████║███████║
            ██║╚██╔╝██║██║   ██║██║     ██╔══██║██╔══██║
            ██║ ╚═╝ ██║╚██████╔╝╚██████╗██║  ██║██║  ██║
            ╚═╝     ╚═╝ ╚═════╝  ╚═════╝╚═╝  ╚═╝╚═╝  ╚═╝""";

    private final ModelConfig modelCfg;
    private ToolCallingAgent agent;
    private AgentBootstrap bootstrap;
    private LLM llm;
    private PrintStream out;
    private PrintStream err;
    private PlanMode planMode;
    private double sessionCost;
    private long sessionInputTokens;
    private long sessionOutputTokens;

    // Diff tracking — capture tool outputs that modify files for real-time display
    private final List<FileChange> pendingChanges = new ArrayList<>();
    private boolean currentRunHasDiff;

    Repl(ModelConfig modelCfg) { this.modelCfg = modelCfg; }

    @Override
    public int run(String[] args, PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;

        printBanner();
        printEnvInfo();
        printCwd();
        printDivider();

        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
            for (;;) {
                String prompt = planMode != null && planMode.isReadOnly()
                        ? "@(plan) " : "> ";
                out.print(cyan(prompt));
                out.flush();

                String line = in.readLine();
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("/")) {
                    if (handleCommand(line.substring(1))) return 0;
                } else {
                    handleTask(line);
                }
            }
        } catch (Exception e) {
            log.error("REPL error", e);
            err.println(red("Error: " + e.getMessage()));
        }
        return 0;
    }

    // ============ Banner & startup ============

    private void printBanner() {
        out.println(cyan(BANNER));
        out.println("  " + bold("MochaAgent v" + VERSION) + " — " + dim("Java agentic coding framework"));
        out.println();
    }

    private void printEnvInfo() {
        String modelName = llm().modelName();
        String modelLabel = modelCfg.hasModels() ? modelName : dim("fallback (use --model flag)");

        out.println("  " + bold("Model:") + "    " + green(modelLabel));
        out.println("  " + bold("Temp:") + "     " + String.format("%.2f", modelCfg.temperature()));
        out.println("  " + bold("MaxTokens:") + " " + modelCfg.maxTokens());
        out.println("  " + bold("OS:") + "       " + System.getProperty("os.name"));
        out.println("  " + bold("Java:") + "     " + System.getProperty("java.version"));

        if (bootstrap != null) {
            out.println("  " + bold("Tools:") + "    " + bootstrap.toolRegistry().size()
                    + (bootstrap.agentTool() != null ? " (+agent tool)" : ""));
        }
    }

    private void printCwd() {
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        String gitBranch = detectGitBranch(cwd);
        String dirName = cwd.getFileName() != null ? cwd.getFileName().toString() : cwd.toString();
        out.print("  " + dim(cwd.toString()));
        if (gitBranch != null) out.print(" " + yellow("(" + gitBranch + ")"));
        out.println();
    }

    private void printDivider() {
        out.println(dim("─".repeat(72)));
    }

    // ============ Task execution ============

    private void handleTask(String task) {
        out.println();
        out.println(dim("⏺ ") + task);
        out.println();

        try {
            var a = agent();
            currentRunHasDiff = false;

            // Real-time event display (claude-code style)
            var unsub = a.onEvent(e -> {
                switch (e.type()) {
                    case io.sketch.mochaagents.agent.AgentEvents.STARTED ->
                        out.print(dim("  Thinking"));
                    case io.sketch.mochaagents.agent.AgentEvents.TOOL_CALL -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> d = (Map<String, Object>) e.data();
                        String toolName = (String) d.get("toolName");
                        String file = (String) d.get("file");
                        if (file != null) {
                            // File-modifying tool — show diff immediately
                            if (!currentRunHasDiff) { out.println(); currentRunHasDiff = true; }
                            String type = (String) d.getOrDefault("type", "modify");
                            out.print("  " + dim("│ ") + colorForType(type) + " " + bold(file));
                            long elapsed = e.elapsedMs();
                            out.println(dim("  (" + elapsed + "ms)"));
                            // Show diff content inline
                            String oldContent = (String) d.get("oldContent");
                            String newContent = (String) d.get("newContent");
                            if (newContent != null) {
                                showInlineDiff(oldContent, newContent);
                            }
                            // Track for summary
                            pendingChanges.add(new FileChange(file,
                                    oldContent, newContent, type, ZonedDateTime.now()));
                        } else {
                            // Non-file tool — brief notification
                            if (!currentRunHasDiff) out.print(".");
                        }
                    }
                    case io.sketch.mochaagents.agent.AgentEvents.COST -> {
                        double[] c = (double[]) e.data();
                        sessionCost += c[0];
                        sessionInputTokens += (long) c[1];
                        sessionOutputTokens += (long) c[2];
                    }
                    case io.sketch.mochaagents.agent.AgentEvents.COMPLETED -> {
                        long elapsed = e.elapsedMs();
                        if (currentRunHasDiff) {
                            out.println(dim("  │"));
                        }
                        out.println(dim("  ✓ " + elapsed + "ms | $"
                                + String.format("%.4f", sessionCost)
                                + " | " + formatTokens(sessionInputTokens) + " in / "
                                + formatTokens(sessionOutputTokens) + " out"));
                    }
                }
            });

            String result = a.run(task);
            unsub.run();

            // Display result
            out.println();
            out.println(result);

            // Check for file changes after execution
            showPendingChanges();

        } catch (Exception e) {
            log.error("Agent error", e);
            out.println(red("  ✗ Error: " + e.getMessage()));
        }
        out.println();
    }

    /** Track a file change for diff display. */
    public void trackFileChange(String file, String oldContent, String newContent, String type) {
        pendingChanges.add(new FileChange(file, oldContent, newContent, type, ZonedDateTime.now()));
    }

    private void showPendingChanges() {
        if (pendingChanges.isEmpty()) return;
        out.println();
        out.println(bold("Changes:"));
        for (FileChange fc : pendingChanges) {
            out.println("  " + colorForType(fc.type) + " " + fc.filePath);
            showDiff(fc);
        }
        pendingChanges.clear();
    }

    /** Compact inline diff for real-time display. */
    private void showInlineDiff(String oldContent, String newContent) {
        if (oldContent == null || oldContent.isEmpty()) {
            // New file — show first 3 lines
            String[] lines = newContent.split("\n");
            int count = 0;
            for (String line : lines) {
                if (count++ >= 3) { out.println(dim("  │   ... +" + (lines.length - 3) + " more lines")); break; }
                out.println(green("  │ + ") + dim(line));
            }
            return;
        }
        // Changed file — show added/removed lines (max 6)
        String[] oldLines = oldContent.split("\n");
        String[] newLines = newContent.split("\n");
        int shown = 0;
        for (int i = 0; i < Math.max(oldLines.length, newLines.length) && shown < 6; i++) {
            String o = i < oldLines.length ? oldLines[i] : null;
            String n = i < newLines.length ? newLines[i] : null;
            if (Objects.equals(o, n)) continue;
            if (o != null && n == null && !o.isBlank())
                { out.println(red("  │ - ") + dim(o)); shown++; }
            else if (n != null && o == null && !n.isBlank())
                { out.println(green("  │ + ") + dim(n)); shown++; }
            else if (o != null) {
                out.println(red("  │ - ") + dim(o));
                out.println(green("  │ + ") + dim(n));
                shown += 2;
            }
        }
        int remaining = Math.abs(oldLines.length - newLines.length);
        if (remaining > 6) out.println(dim("  │   ... " + remaining + " more differing lines"));
    }

    private void showDiff(FileChange fc) {
        if (fc.oldContent == null || fc.oldContent.isEmpty()) {
            // New file
            out.println(green("  +++ " + fc.filePath));
            for (String line : fc.newContent.split("\n")) {
                if (!line.isBlank()) out.println(green("  + ") + dim(line));
            }
            return;
        }
        // Simple line diff
        String[] oldLines = fc.oldContent.split("\n");
        String[] newLines = fc.newContent.split("\n");
        int maxLen = Math.max(oldLines.length, newLines.length);
        for (int i = 0; i < maxLen; i++) {
            String o = i < oldLines.length ? oldLines[i] : null;
            String n = i < newLines.length ? newLines[i] : null;
            if (Objects.equals(o, n)) continue; // skip unchanged lines
            if (o != null && n == null) out.println(red("  - ") + dim(o));
            else if (o == null && n != null) out.println(green("  + ") + dim(n));
            else {
                out.println(red("  - ") + dim(o));
                out.println(green("  + ") + dim(n));
            }
        }
    }

    // ============ Slash commands ============

    private boolean handleCommand(String cmd) {
        String[] parts = cmd.split("\\s+", 2);
        String name = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";

        return switch (name) {
            case "help", "h" -> { showHelp(); yield false; }
            case "exit", "quit", "q" -> { out.println("Goodbye."); yield true; }
            case "version" -> { out.println(VERSION); yield false; }
            case "model" -> { showModelInfo(); yield false; }
            case "cost" -> { showCost(); yield false; }
            case "clear" -> { clearSession(); yield false; }
            case "compact" -> { compactContext(); yield false; }
            case "plan" -> { enterPlanMode(); yield false; }
            case "exitplan" -> { exitPlanMode(); yield false; }
            case "diff" -> { showDiffCmd(arg); yield false; }
            case "status" -> { showStatus(); yield false; }
            case "tools" -> { showTools(); yield false; }
            default -> { out.println(red("Unknown command: /" + name + " (use /help)")); yield false; }
        };
    }

    private void showHelp() {
        out.println();
        out.println(bold("Commands:"));
        String[][] commands = {
            {"/help", "Show this help"},
            {"/model", "Show model configuration"},
            {"/cost", "Show session cost and token usage"},
            {"/clear", "Clear conversation context"},
            {"/compact", "Compact context window"},
            {"/plan", "Enter plan mode (read-only exploration)"},
            {"/exitplan", "Exit plan mode"},
            {"/diff [file]", "Show pending file changes"},
            {"/status", "Show agent status"},
            {"/tools", "List available tools"},
            {"/exit, /quit", "Exit REPL"},
        };
        for (String[] c : commands) {
            out.println("  " + bold(String.format("%-18s", c[0])) + dim(c[1]));
        }
        out.println();
    }

    private void showModelInfo() {
        LLM l = llm();
        out.println(bold("Model:") + " " + green(l.modelName()));
        out.println(bold("Context:") + " " + l.maxContextTokens() + " tokens");
        out.println(bold("Temp:") + " " + String.format("%.2f", modelCfg.temperature()));
    }

    private void showCost() {
        out.println(bold("Session cost:") + " $" + String.format("%.4f", sessionCost));
        out.println(bold("Input tokens:") + " " + formatTokens(sessionInputTokens));
        out.println(bold("Output tokens:") + " " + formatTokens(sessionOutputTokens));
    }

    private void clearSession() {
        sessionCost = 0;
        sessionInputTokens = 0;
        sessionOutputTokens = 0;
        pendingChanges.clear();
        agent = null; // force recreate
        out.println(green("✓ Context cleared"));
    }

    private void compactContext() {
        if (agent != null) {
            agent.autoCompact();
            out.println(green("✓ Context compacted"));
        } else {
            out.println(dim("No active session to compact"));
        }
    }

    private void enterPlanMode() {
        if (planMode == null) planMode = new PlanMode();
        if (planMode.enterPlan()) {
            out.println(green("✓ Entered plan mode — read-only exploration"));
            out.println(dim("  Phase 1: UNDERSTAND — read code, explore, ask questions"));
        } else {
            out.println(yellow("Already in plan mode"));
        }
    }

    private void exitPlanMode() {
        if (planMode != null && planMode.isReadOnly()) {
            planMode.exitPlan(planMode.currentPlan() != null ? planMode.currentPlan() : "");
            out.println(green("✓ Exited plan mode — write operations now enabled"));
        } else {
            out.println(yellow("Not in plan mode"));
        }
    }

    private void showDiffCmd(String fileFilter) {
        if (pendingChanges.isEmpty()) {
            out.println(dim("No pending file changes"));
            return;
        }
        for (FileChange fc : pendingChanges) {
            if (fileFilter.isEmpty() || fc.filePath.contains(fileFilter)) {
                showDiff(fc);
            }
        }
    }

    private void showStatus() {
        out.println(bold("Agent:") + " " + (agent != null ? agent.metadata().name() : "not loaded"));
        out.println(bold("Tools:") + " " + (bootstrap != null ? bootstrap.toolRegistry().size() : 0));
        out.println(bold("Plan mode:") + " " + (planMode != null && planMode.isReadOnly() ? "active" : "inactive"));
        out.println(bold("Session:") + " $" + String.format("%.4f", sessionCost)
                + " | " + formatTokens(sessionInputTokens + sessionOutputTokens) + " tokens");
    }

    private void showTools() {
        if (bootstrap == null) { out.println(dim("No tools loaded")); return; }
        out.println(bold("Available tools:"));
        for (var tool : bootstrap.toolRegistry().all()) {
            out.println("  " + bold(tool.getName()) + " — " + dim(tool.getDescription()));
        }
    }

    // ============ Agent lifecycle ============

    private ToolCallingAgent agent() {
        if (agent == null) {
            bootstrap = AgentBootstrap.init();
            llm = modelCfg.build();
            agent = ToolCallingAgent.builder()
                    .name("repl-agent").llm(llm)
                    .toolRegistry(bootstrap.toolRegistry())
                    .maxSteps(modelCfg.maxTokens() > 0 ? 20 : 10)
                    .build();
            log.info("REPL agent created — model: {}", llm.modelName());
        }
        return agent;
    }

    private LLM llm() {
        if (llm == null) llm = modelCfg.build();
        return llm;
    }

    // ============ Git detection ============

    private static String detectGitBranch(Path cwd) {
        try {
            Path gitDir = cwd.resolve(".git");
            if (!Files.isDirectory(gitDir)) {
                // Search upward
                Path current = cwd;
                while (current != null) {
                    if (Files.isDirectory(current.resolve(".git"))) {
                        gitDir = current.resolve(".git");
                        break;
                    }
                    current = current.getParent();
                }
                if (current == null) return null;
            }
            String head = Files.readString(gitDir.resolve("HEAD")).trim();
            if (head.startsWith("ref: refs/heads/")) {
                return head.substring("ref: refs/heads/".length());
            }
            return head.substring(0, Math.min(7, head.length())); // detached HEAD
        } catch (Exception e) { return null; }
    }

    // ============ ANSI helpers ============

    private static String green(String s)  { return "\033[32m" + s + "\033[0m"; }
    private static String red(String s)    { return "\033[31m" + s + "\033[0m"; }
    private static String yellow(String s) { return "\033[33m" + s + "\033[0m"; }
    private static String cyan(String s)   { return "\033[36m" + s + "\033[0m"; }
    private static String dim(String s)    { return "\033[2m" + s + "\033[0m"; }
    private static String bold(String s)   { return "\033[1m" + s + "\033[0m"; }

    private static String colorForType(String type) {
        return switch (type) {
            case "create" -> green("+ create");
            case "modify" -> yellow("~ modify");
            case "delete" -> red("- delete");
            default -> dim("? " + type);
        };
    }

    private static String formatTokens(long tokens) {
        if (tokens < 1000) return tokens + "tk";
        if (tokens < 1_000_000) return String.format("%.1fk", tokens / 1000.0);
        return String.format("%.1fM", tokens / 1_000_000.0);
    }

    // ============ File change tracking ============

    record FileChange(String filePath, String oldContent, String newContent,
                      String type, ZonedDateTime timestamp) {}
}
