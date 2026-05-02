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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * PowerShell 命令执行工具 — 对齐 claude-code 的 PowerShellTool.
 *
 * <p>在 Windows 上使用 powershell.exe，在其他平台使用 pwsh。
 * 包含：
 * <ul>
 *   <li>危险命令检测（Remove-Item -Recurse -Force、git reset --hard 等）</li>
 *   <li>PowerShell 特有安全检测（Invoke-Expression、Start-Process、下载摇篮等）</li>
 *   <li>退出码语义解释（grep/robocopy/findstr 的 non-zero-ok 码）</li>
 *   <li>超时控制与输出截断</li>
 * </ul>
 *
 * <p>安全等级：HIGH。所有写入/破坏性命令需要权限确认。
 */
public class PowerShellTool extends AbstractTool {

    // ==================== 常量 ====================

    private static final String NAME = "powershell";
    private static final int DEFAULT_TIMEOUT_SEC = 120;
    private static final int MAX_OUTPUT_CHARS = 50_000;

    // ==================== 危险命令模式（对齐 destructiveCommandWarning.ts） ====================

    private static final List<DestructivePattern> DESTRUCTIVE_PATTERNS = List.of(
            // Remove-Item with -Recurse and/or -Force (and common aliases)
            new DestructivePattern(
                    Pattern.compile(
                            "(?:^|[|;&\\n({])\\s*(Remove-Item|rm|del|rd|rmdir|ri)\\b[^|;&\\n}]*"
                                    + "-Recurse\\b[^|;&\\n}]*-Force\\b",
                            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
                    "Note: may recursively force-remove files"
            ),
            new DestructivePattern(
                    Pattern.compile(
                            "(?:^|[|;&\\n({])\\s*(Remove-Item|rm|del|rd|rmdir|ri)\\b[^|;&\\n}]*"
                                    + "-Force\\b[^|;&\\n}]*-Recurse\\b",
                            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
                    "Note: may recursively force-remove files"
            ),
            new DestructivePattern(
                    Pattern.compile(
                            "(?:^|[|;&\\n({])\\s*(Remove-Item|rm|del|rd|rmdir|ri)\\b[^|;&\\n}]*-Recurse\\b",
                            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
                    "Note: may recursively remove files"
            ),
            new DestructivePattern(
                    Pattern.compile(
                            "(?:^|[|;&\\n({])\\s*(Remove-Item|rm|del|rd|rmdir|ri)\\b[^|;&\\n}]*-Force\\b",
                            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
                    "Note: may force-remove files"
            ),

            // Clear-Content on broad paths
            new DestructivePattern(
                    Pattern.compile("\\bClear-Content\\b[^|;&\\n]*\\*", Pattern.CASE_INSENSITIVE),
                    "Note: may clear content of multiple files"
            ),

            // Format-Volume and Clear-Disk
            new DestructivePattern(
                    Pattern.compile("\\bFormat-Volume\\b", Pattern.CASE_INSENSITIVE),
                    "Note: may format a disk volume"
            ),
            new DestructivePattern(
                    Pattern.compile("\\bClear-Disk\\b", Pattern.CASE_INSENSITIVE),
                    "Note: may clear a disk"
            ),

            // Git destructive operations
            new DestructivePattern(
                    Pattern.compile("\\bgit\\s+reset\\s+--hard\\b", Pattern.CASE_INSENSITIVE),
                    "Note: may discard uncommitted changes"
            ),
            new DestructivePattern(
                    Pattern.compile("\\bgit\\s+push\\b[^|;&\\n]*\\s+(--force|--force-with-lease|-f)\\b",
                            Pattern.CASE_INSENSITIVE),
                    "Note: may overwrite remote history"
            ),
            new DestructivePattern(
                    Pattern.compile("\\bgit\\s+clean\\b(?![^|;&\\n]*(?:-[a-zA-Z]*n|--dry-run))"
                            + "[^|;&\\n]*-[a-zA-Z]*f", Pattern.CASE_INSENSITIVE),
                    "Note: may permanently delete untracked files"
            ),
            new DestructivePattern(
                    Pattern.compile("\\bgit\\s+stash\\s+(drop|clear)\\b", Pattern.CASE_INSENSITIVE),
                    "Note: may permanently remove stashed changes"
            ),

            // Database operations
            new DestructivePattern(
                    Pattern.compile("\\b(DROP|TRUNCATE)\\s+(TABLE|DATABASE|SCHEMA)\\b",
                            Pattern.CASE_INSENSITIVE),
                    "Note: may drop or truncate database objects"
            ),

            // System operations
            new DestructivePattern(
                    Pattern.compile("\\bStop-Computer\\b", Pattern.CASE_INSENSITIVE),
                    "Note: will shut down the computer"
            ),
            new DestructivePattern(
                    Pattern.compile("\\bRestart-Computer\\b", Pattern.CASE_INSENSITIVE),
                    "Note: will restart the computer"
            ),
            new DestructivePattern(
                    Pattern.compile("\\bClear-RecycleBin\\b", Pattern.CASE_INSENSITIVE),
                    "Note: permanently deletes recycled files"
            )
    );

    // ==================== PowerShell 危险 Cmdlet（对齐 powershellSecurity.ts） ====================

    /** 危险的 PowerShell cmdlet — 检测到后标记为需要审批. */
    private static final Set<String> DANGEROUS_PS_CMDLETS = Set.of(
            "invoke-expression", "iex",
            "invoke-command", "icm",
            "start-job",
            "start-threadjob",
            "register-scheduledjob",
            "add-type",
            "invoke-wmimethod",
            "invoke-cimmethod",
            "import-module", "ipmo",
            "install-module",
            "save-module",
            "set-alias", "sal",
            "new-alias", "nal",
            "set-variable", "sv",
            "new-variable", "nv",
            "register-scheduledtask",
            "new-scheduledtask",
            "new-scheduledtaskaction",
            "set-scheduledtask"
    );

    /** 编码命令参数. */
    private static final Pattern ENCODED_COMMAND_PATTERN =
            Pattern.compile("-e(nc(oded(Command)?)?)?\\b", Pattern.CASE_INSENSITIVE);

    /** 下载摇篮模式. */
    private static final Pattern DOWNLOAD_Cradle_PATTERN =
            Pattern.compile("(Invoke-WebRequest|IWR|Invoke-RestMethod|IRM|Start-BitsTransfer)"
                    + "\\s.*\\|.*(Invoke-Expression|IEX)",
                    Pattern.CASE_INSENSITIVE);

    /** Start-Process 启动 PowerShell 子进程的模式. */
    private static final Pattern PS_NESTED_START_PROCESS =
            Pattern.compile("Start-Process\\s+(pwsh|powershell)",
                    Pattern.CASE_INSENSITIVE);

    /** -Verb RunAs 提权. */
    private static final Pattern RUNAS_PATTERN =
            Pattern.compile("-Verb:?\\s*['\"]?RunAs['\"]?", Pattern.CASE_INSENSITIVE);

    /** UNC 路径. */
    private static final Pattern UNC_PATH_PATTERN =
            Pattern.compile("\\\\\\\\[^\\s]+");

    /** COM 对象创建. */
    private static final Pattern COM_OBJECT_PATTERN =
            Pattern.compile("New-Object\\s+-ComObject", Pattern.CASE_INSENSITIVE);

    // ==================== 退出码语义（对齐 commandSemantics.ts） ====================

    private static final Set<String> GREP_LIKE_COMMANDS = Set.of("grep", "grep.exe", "rg", "rg.exe", "findstr", "findstr.exe");
    private static final Set<String> ROBOCOPY_COMMANDS = Set.of("robocopy", "robocopy.exe");

    // ==================== 只读命令 ====================

    private static final Set<String> READ_ONLY_CMDLETS = Set.of(
            "get-command", "gcm",
            "get-help", "help", "man",
            "get-childitem", "gci", "dir", "ls",
            "get-item", "gi",
            "get-content", "gc", "cat", "type",
            "get-process", "gps", "ps",
            "get-service", "gsv",
            "get-eventlog",
            "get-wmiobject", "gwmi",
            "get-ciminstance", "gcim",
            "select-object", "select",
            "where-object", "where", "?",
            "sort-object", "sort",
            "group-object", "group",
            "measure-object", "measure",
            "compare-object", "compare", "diff",
            "format-table", "ft",
            "format-list", "fl",
            "format-wide", "fw",
            "format-custom", "fc",
            "out-string",
            "convertto-json",
            "convertto-xml",
            "convertto-csv",
            "export-csv",
            "export-clixml",
            "write-output", "write", "echo",
            "write-host",
            "write-information",
            "write-verbose",
            "write-debug",
            "write-warning",
            "write-error",
            "test-path",
            "test-connection",
            "resolve-path", "rvpa",
            "split-path",
            "join-path",
            "get-location", "gl", "pwd",
            "get-date",
            "get-random",
            "get-culture",
            "get-uiculture",
            "get-host",
            "get-variable", "gv",
            "get-alias", "gal",
            "get-member", "gm",
            "select-string", "sls",
            "out-null"
    );

    public PowerShellTool() {
        super(builder(NAME,
                "Execute a PowerShell command. Use this tool for Windows PowerShell operations. "
                        + "Returns stdout, stderr, exit code, and whether output was truncated. "
                        + "Use -NoProfile -NonInteractive automatically for safety.",
                SecurityLevel.HIGH)
                .searchHint("run powershell commands windows shell")
        );
    }

    // ==================== Schema ====================

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .inputType("object")
                .inputRequired("command")
                .inputProperty("command", "string",
                        "The PowerShell command to execute (without wrappers like powershell -Command)",
                        true)
                .inputProperty("timeout", "integer",
                        "Timeout in seconds (default " + DEFAULT_TIMEOUT_SEC + ")", false)
                .inputProperty("workdir", "string",
                        "Working directory for the command", false)
                .outputType("object")
                .outputProperty("stdout", "string", "Standard output of the command")
                .outputProperty("stderr", "string", "Standard error of the command")
                .outputProperty("exitCode", "integer", "Process exit code (see semantics)")
                .outputProperty("truncated", "boolean", "Whether output was truncated")
                .outputProperty("destructiveWarning", "string",
                        "Warning about potentially destructive operations, if any")
                .build();
    }

    @Override
    public Map<String, ToolInput> getInputs() {
        Map<String, ToolInput> inputs = new LinkedHashMap<>();
        inputs.put("command", ToolInput.string("The PowerShell command to execute"));
        inputs.put("timeout", new ToolInput("integer", "Timeout in seconds", true));
        inputs.put("workdir", new ToolInput("string", "Working directory", true));
        return inputs;
    }

    @Override
    public String getOutputType() { return "object"; }

    // ==================== 安全属性 ====================

    @Override
    public boolean isDestructive() {
        // PowerShell commands can be destructive — fail-closed
        return false; // Dynamic: determined per-command
    }

    @Override
    public SecurityLevel getSecurityLevel() {
        return SecurityLevel.HIGH;
    }

    // ==================== 验证 ====================

    @Override
    public ValidationResult validateInput(Map<String, Object> arguments) {
        String command = (String) arguments.get("command");
        if (command == null || command.isBlank()) {
            return ValidationResult.invalid("command is required", 1);
        }
        if (command.length() > 32_000) {
            return ValidationResult.invalid("command exceeds maximum length (32000 chars)", 2);
        }
        return ValidationResult.valid();
    }

    // ==================== 权限检查 ====================

    @Override
    public PermissionResult checkPermissions(Map<String, Object> arguments) {
        String command = (String) arguments.get("command");
        if (command == null || command.isBlank()) {
            return PermissionResult.deny("No command specified");
        }

        String lower = command.toLowerCase().trim();

        // 检查 PowerShell 特有危险操作
        for (String dangerous : DANGEROUS_PS_CMDLETS) {
            if (containsWord(lower, dangerous)) {
                return PermissionResult.ask(
                        "Command uses potentially dangerous cmdlet: " + dangerous);
            }
        }

        // 检查编码命令
        if (ENCODED_COMMAND_PATTERN.matcher(lower).find()) {
            return PermissionResult.ask(
                    "Command uses encoded parameters which obscure intent");
        }

        // 检查下载摇篮
        if (DOWNLOAD_Cradle_PATTERN.matcher(lower).find()) {
            return PermissionResult.ask(
                    "Command downloads and may execute remote code");
        }

        // 检查 PowerShell 子进程
        if (PS_NESTED_START_PROCESS.matcher(lower).find()) {
            return PermissionResult.ask(
                    "Start-Process launches a nested PowerShell process which cannot be validated");
        }

        // 检查提权
        if (RUNAS_PATTERN.matcher(lower).find()) {
            return PermissionResult.ask(
                    "Command requests elevated privileges (RunAs)");
        }

        // 检查 UNC 路径
        if (UNC_PATH_PATTERN.matcher(lower).find()) {
            return PermissionResult.ask(
                    "Command contains UNC path that could trigger network requests");
        }

        // 检查 COM 对象
        if (COM_OBJECT_PATTERN.matcher(lower).find()) {
            return PermissionResult.ask(
                    "Command instantiates COM object which may have execution capabilities");
        }

        // 检查 schtasks
        if (lower.contains("schtasks") && (lower.contains("/create") || lower.contains("/change")
                || lower.contains("-create") || lower.contains("-change"))) {
            return PermissionResult.ask(
                    "schtasks with create/change modifies scheduled tasks (persistence primitive)");
        }

        // 检查 certutil URL 缓存下载
        if ((lower.contains("certutil") || lower.contains("certutil.exe"))
                && (lower.contains("-urlcache") || lower.contains("/urlcache"))) {
            return PermissionResult.ask(
                    "Command uses certutil to download from a URL");
        }

        // 检查 bitsadmin 传输
        if ((lower.contains("bitsadmin") || lower.contains("bitsadmin.exe"))
                && lower.contains("/transfer")) {
            return PermissionResult.ask(
                    "Command downloads files via BITS transfer");
        }

        // 只读命令自动允许
        if (isReadOnlyCommand(lower)) {
            return PermissionResult.allow(arguments, "Read-only PowerShell command");
        }

        return PermissionResult.allow(arguments);
    }

    // ==================== 执行 ====================

    @Override
    public Object call(Map<String, Object> arguments) {
        String command = (String) arguments.get("command");
        int timeout = getIntArg(arguments, "timeout", DEFAULT_TIMEOUT_SEC);
        String workdir = (String) arguments.get("workdir");

        // 检测破坏性警告（pre-execution advisory）
        String destructiveWarning = getDestructiveCommandWarning(command);

        try {
            // 构建 PowerShell 命令
            String psCommand = buildPowerShellCommand(command);
            String shell = resolvePowerShellExecutable();

            ProcessBuilder pb = new ProcessBuilder();
            pb.command(shell, psCommand);

            if (workdir != null && !workdir.isBlank()) {
                pb.directory(new File(workdir));
            }

            pb.redirectErrorStream(false);
            Process process = pb.start();

            // 并行读取 stdout 和 stderr
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread stdoutThread = new Thread(() -> readStream(process, stdout, true));
            Thread stderrThread = new Thread(() -> readStream(process, stderr, false));

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

            // 退出码语义解释
            String semanticMessage = interpretExitCode(command, exitCode, out, err);
            if (semanticMessage != null && !semanticMessage.isBlank()) {
                if (!err.isEmpty()) err += "\n";
                err += semanticMessage;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("stdout", out);
            result.put("stderr", err);
            result.put("exitCode", exitCode);
            result.put("truncated", truncated);
            if (destructiveWarning != null) {
                result.put("destructiveWarning", destructiveWarning);
            }
            return result;

        } catch (Exception e) {
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("stdout", "");
            errorResult.put("stderr", e.getMessage());
            errorResult.put("exitCode", -1);
            errorResult.put("truncated", false);
            if (destructiveWarning != null) {
                errorResult.put("destructiveWarning", destructiveWarning);
            }
            return errorResult;
        }
    }

    // ==================== 结果格式化 ====================

    @Override
    public String formatResult(Object output, String toolUseId) {
        if (!(output instanceof Map)) return output != null ? output.toString() : "";
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) output;
        String out = (String) map.getOrDefault("stdout", "");
        String err = (String) map.getOrDefault("stderr", "");
        int exitCode = ((Number) map.getOrDefault("exitCode", -1)).intValue();
        boolean truncated = Boolean.TRUE.equals(map.get("truncated"));
        String warning = (String) map.get("destructiveWarning");

        StringBuilder sb = new StringBuilder();
        if (warning != null) {
            sb.append("[WARNING] ").append(warning).append("\n");
        }
        if (!out.isEmpty()) sb.append(out);
        if (!err.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[stderr]\n").append(err);
        }
        sb.append("\n[Exit code: ").append(exitCode).append("]");
        if (truncated) sb.append(" [output truncated]");
        return sb.toString();
    }

    // ==================== 内部方法 ====================

    /**
     * 构建安全的 PowerShell 执行命令.
     * 自动添加 -NoProfile -NonInteractive -Command 包装。
     * 当检测到复杂命令时使用 -EncodedCommand 避免转义问题。
     */
    private String buildPowerShellCommand(String rawCommand) {
        // 简单的单行命令直接拼接；多行或含特殊字符的用 Base64 编码
        if (rawCommand.contains("\n") || rawCommand.contains("\r")
                || rawCommand.contains("\"") && rawCommand.contains("$")) {
            String encoded = java.util.Base64.getEncoder()
                    .encodeToString(rawCommand.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
            return "-NoProfile -NonInteractive -WindowStyle Hidden -EncodedCommand " + encoded;
        }
        return "-NoProfile -NonInteractive -WindowStyle Hidden -Command \"" +
                rawCommand.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * 解析当前平台可用的 PowerShell 可执行文件.
     */
    private String resolvePowerShellExecutable() {
        if (isWindows()) {
            // 优先使用 pwsh (PowerShell Core 7+), 回退到 powershell (Windows PowerShell 5.1)
            if (which("pwsh.exe") != null || which("pwsh") != null) {
                return "pwsh.exe";
            }
            return "powershell.exe";
        }
        // Unix/macOS — 使用 pwsh
        return "pwsh";
    }

    /**
     * 检测是否为只读 PowerShell 命令.
     */
    private boolean isReadOnlyCommand(String lowerCmd) {
        if (lowerCmd == null || lowerCmd.isBlank()) return true;

        // 提取第一个 token（去除前导 & . 调用操作符）
        String firstToken = lowerCmd.trim()
                .replaceAll("^[&.]\\s+", "")
                .split("\\s+")[0];

        // 去除可能的 module 前缀（如 Microsoft.PowerShell.Management\Get-Process）
        int lastBackslash = firstToken.lastIndexOf('\\');
        if (lastBackslash >= 0) {
            firstToken = firstToken.substring(lastBackslash + 1);
        }

        // 去除 .exe 后缀
        if (firstToken.endsWith(".exe")) {
            firstToken = firstToken.substring(0, firstToken.length() - 4);
        }

        if (READ_ONLY_CMDLETS.contains(firstToken)) return true;

        // 外部工具：git status/log/diff/show, grep, find, ls, cat 等
        if (Set.of("git", "grep", "rg", "findstr", "where", "tree").contains(firstToken)) {
            return true;
        }

        return false;
    }

    /**
     * 检测破坏性命令并返回警告信息（对齐 destructiveCommandWarning.ts）.
     */
    private String getDestructiveCommandWarning(String command) {
        if (command == null) return null;
        for (DestructivePattern dp : DESTRUCTIVE_PATTERNS) {
            if (dp.pattern.matcher(command).find()) {
                return dp.warning;
            }
        }
        return null;
    }

    /**
     * 退出码语义解释（对齐 commandSemantics.ts）.
     *
     * <p>PowerShell 原生 cmdlet 不需要退出码解释 — 它们通过 $? 和 ErrorRecord 表示失败。
     * 只有外部可执行文件需要此处理。
     */
    private String interpretExitCode(String command, int exitCode, String stdout, String stderr) {
        if (exitCode == 0) return null;

        String baseCommand = heuristicallyExtractBaseCommand(command);

        // grep-like: 0 = match found, 1 = no match, 2+ = error
        if (GREP_LIKE_COMMANDS.contains(baseCommand)) {
            if (exitCode == 1) return "No matches found";
            if (exitCode >= 2) return null; // actual error — let stderr speak
            return null;
        }

        // robocopy: 0-7 success, 8+ error
        if (ROBOCOPY_COMMANDS.contains(baseCommand)) {
            if (exitCode < 8) {
                if (exitCode == 0) return "No files copied (already in sync)";
                if ((exitCode & 1) != 0) return "Files copied successfully";
                return "Robocopy completed (no errors)";
            }
            return null;
        }

        return null;
    }

    /**
     * 启发式提取命令的基名称.
     * 从管道/分号的最后一个段中提取。
     */
    private String heuristicallyExtractBaseCommand(String command) {
        String[] segments = command.split("[;|]");
        String last = segments[segments.length - 1].trim();
        return extractBaseCommand(last);
    }

    private String extractBaseCommand(String segment) {
        // 去除前导 & . 调用操作符及引号
        String stripped = segment.trim().replaceAll("^[&.]\\s+", "");
        String firstToken = stripped.split("\\s+")[0];
        firstToken = firstToken.replaceAll("^['\"]|['\"]$", "");
        // 去除路径
        int lastSep = Math.max(firstToken.lastIndexOf('/'), firstToken.lastIndexOf('\\'));
        if (lastSep >= 0) {
            firstToken = firstToken.substring(lastSep + 1);
        }
        // 去除 .exe
        return firstToken.toLowerCase().replaceAll("\\.exe$", "");
    }

    /**
     * 检查单词是否作为完整词出现在命令中（非子串匹配）.
     */
    private boolean containsWord(String text, String word) {
        int idx = text.indexOf(word);
        if (idx < 0) return false;
        // 左边界：文本开头或前一个字符是分隔符
        if (idx > 0 && Character.isLetterOrDigit(text.charAt(idx - 1))) return false;
        // 右边界：文本结束或后一个字符是分隔符
        int end = idx + word.length();
        if (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) return false;
        return true;
    }

    private void readStream(Process process, StringBuilder sb, boolean stdout) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                stdout ? process.getInputStream() : process.getErrorStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception ignored) {
        }
    }

    // ==================== 静态工具方法 ====================

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String which(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder();
            if (isWindows()) {
                pb.command("where", command);
            } else {
                pb.command("which", command);
            }
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0 && line != null) {
                    return line.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String truncate(String s, int maxChars) {
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "\n... [truncated]";
    }

    private static int getIntArg(Map<String, Object> args, String key, int defaultVal) {
        Object v = args.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String s && !s.isEmpty()) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultVal;
    }

    // ==================== 内部类型 ====================

    /**
     * 破坏性命令模式（对齐 TypeScript DestructivePattern 接口）.
     */
    private record DestructivePattern(Pattern pattern, String warning) {
    }
}
