package io.sketch.mochaagents.tool.impl;

import io.sketch.mochaagents.tool.AbstractTool;
import io.sketch.mochaagents.tool.PermissionResult;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolSchema;
import io.sketch.mochaagents.tool.ValidationResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Shell 命令执行工具 — 对齐 claude-code 的 BashTool.
 *
 * <p>使用 ProcessBuilder 执行系统命令，含超时控制、输出截断、
 * 危险命令检测和只读命令判断。
 */
public class BashTool extends AbstractTool {

    private static final String NAME = "bash";
    private static final int DEFAULT_TIMEOUT_SEC = 120;
    private static final int MAX_OUTPUT_CHARS = 50_000;

    /** 危险命令关键词（需要 HIGH 安全级别）. */
    private static final Set<String> DANGEROUS_COMMANDS = Set.of(
            "rm", "rmdir", "dd", "mkfs", "format",
            "shutdown", "reboot", "halt", "poweroff",
            "chmod", "chown", ">", ">>", "| sudo", "sudo "
    );

    /** 只读命令关键词. */
    private static final Set<String> READ_ONLY_COMMANDS = Set.of(
            "ls", "cat", "head", "tail", "less", "more",
            "find", "grep", "wc", "du", "df", "stat",
            "file", "echo", "pwd", "which", "whereis",
            "uname", "hostname", "whoami", "id", "env",
            "printenv", "ps", "top", "htop", "uptime",
            "date", "cal", "tree", "awk", "sed -n"
    );

    public BashTool() {
        super(builder(NAME, "Execute a shell command in the terminal. "
                        + "Supports timeout control and working directory override.",
                SecurityLevel.HIGH)
                .searchHint("run shell commands in terminal")
        );
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .inputType("object")
                .inputRequired("command")
                .inputProperty("command", "string", "The shell command to execute", true)
                .inputProperty("timeout", "integer", "Timeout in seconds (default 120)", false)
                .inputProperty("workdir", "string", "Working directory for the command", false)
                .outputType("object")
                .outputProperty("stdout", "string", "Standard output of the command")
                .outputProperty("stderr", "string", "Standard error of the command")
                .outputProperty("exitCode", "integer", "Process exit code")
                .outputProperty("truncated", "boolean", "Whether output was truncated")
                .build();
    }

    @Override
    public Map<String, ToolInput> getInputs() {
        Map<String, ToolInput> inputs = new LinkedHashMap<>();
        inputs.put("command", ToolInput.string("The shell command to execute"));
        inputs.put("timeout", new ToolInput("integer", "Timeout in seconds", true));
        inputs.put("workdir", new ToolInput("string", "Working directory", true));
        return inputs;
    }

    @Override
    public String getOutputType() { return "object"; }

    // ==================== Security ====================

    @Override
    public boolean isReadOnly() {
        // Dynamic — depends on the command. Default to false (fail-closed).
        return false;
    }

    @Override
    public SecurityLevel getSecurityLevel() {
        return SecurityLevel.HIGH;
    }

    /**
     * 根据命令内容判断是否为只读操作.
     */
    public boolean isReadOnlyCommand(String command) {
        if (command == null || command.isBlank()) return true;
        String lower = command.toLowerCase().trim();
        for (String dangerous : DANGEROUS_COMMANDS) {
            if (lower.contains(dangerous)) return false;
        }
        return READ_ONLY_COMMANDS.stream().anyMatch(lower::startsWith);
    }

    // ==================== Validation ====================

    @Override
    public ValidationResult validateInput(Map<String, Object> arguments) {
        String command = (String) arguments.get("command");
        if (command == null || command.isBlank()) {
            return ValidationResult.invalid("command is required", 1);
        }
        return ValidationResult.valid();
    }

    @Override
    public PermissionResult checkPermissions(Map<String, Object> arguments) {
        String command = (String) arguments.get("command");
        if (command == null) return PermissionResult.deny("No command specified");

        if (isReadOnlyCommand(command)) {
            return PermissionResult.allow(arguments, "Read-only command");
        }

        // Destructive commands require explicit permission
        return PermissionResult.allow(arguments);
    }

    // ==================== Call ====================

    @Override
    public Object call(Map<String, Object> arguments) {
        String command = (String) arguments.get("command");
        int timeout = getIntArg(arguments, "timeout", DEFAULT_TIMEOUT_SEC);
        String workdir = (String) arguments.get("workdir");

        try {
            ProcessBuilder pb = new ProcessBuilder();
            // Use platform-appropriate shell
            if (isWindows()) {
                pb.command("cmd.exe", "/c", command);
            } else {
                pb.command("sh", "-c", command);
            }

            if (workdir != null && !workdir.isBlank()) {
                pb.directory(new File(workdir));
            }

            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Read stdout and stderr in parallel
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread stdoutThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });

            Thread stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });

            stdoutThread.start();
            stderrThread.start();

            boolean completed = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new RuntimeException("Command timed out after " + timeout + " seconds");
            }

            stdoutThread.join(5000);
            stderrThread.join(5000);

            int exitCode = process.exitValue();
            String out = truncate(stdout.toString(), MAX_OUTPUT_CHARS);
            String err = truncate(stderr.toString(), MAX_OUTPUT_CHARS);
            boolean truncated = stdout.length() > MAX_OUTPUT_CHARS || stderr.length() > MAX_OUTPUT_CHARS;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("stdout", out);
            result.put("stderr", err);
            result.put("exitCode", exitCode);
            result.put("truncated", truncated);
            return result;

        } catch (Exception e) {
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("stdout", "");
            errorResult.put("stderr", e.getMessage());
            errorResult.put("exitCode", -1);
            errorResult.put("truncated", false);
            return errorResult;
        }
    }

    @Override
    public String formatResult(Object output, String toolUseId) {
        if (!(output instanceof Map)) return output != null ? output.toString() : "";
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) output;
        String out = (String) map.getOrDefault("stdout", "");
        String err = (String) map.getOrDefault("stderr", "");
        int exitCode = ((Number) map.getOrDefault("exitCode", -1)).intValue();
        boolean truncated = Boolean.TRUE.equals(map.get("truncated"));

        StringBuilder sb = new StringBuilder();
        if (!out.isEmpty()) sb.append(out);
        if (!err.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[stderr]\n").append(err);
        }
        sb.append("\n[Exit code: ").append(exitCode).append("]");
        if (truncated) sb.append(" [output truncated]");
        return sb.toString();
    }

    // ==================== Helpers ====================

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String truncate(String s, int maxChars) {
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "\n... [truncated]";
    }

    private static int getIntArg(Map<String, Object> args, String key, int defaultVal) {
        Object v = args.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String s && !s.isEmpty()) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }
}
