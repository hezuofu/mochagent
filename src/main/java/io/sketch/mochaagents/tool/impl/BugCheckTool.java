package io.sketch.mochaagents.tool.impl;

import io.sketch.mochaagents.tool.AbstractTool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolSchema;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Bug 检查工具 — 对代码进行静态分析，检测常见 bug 模式.
 *
 * <p>Agent 可调用此工具在代码生成后、执行前进行检查.
 * 检测: null 安全/资源泄漏/SQL注入/并发问题/无限循环/异常吞没.
 * @author lanxia39@163.com
 */
public class BugCheckTool extends AbstractTool {

    private static final Pattern NULL_DEREF = Pattern.compile(
            "\\.(get|getOrDefault|stream|iterator)\\s*\\(\\s*\\)\\s*\\.",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UNCLOSED_STREAM = Pattern.compile(
            "(FileInputStream|FileOutputStream|BufferedReader|BufferedWriter|Socket|Connection)\\s+\\w+\\s*=",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_INJECTION = Pattern.compile(
            "(\"\\s*\\+\\s*\\w+|'\\s*\\+\\s*\\w+).*?(SELECT|INSERT|DELETE|UPDATE|DROP)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EMPTY_CATCH = Pattern.compile(
            "catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}");
    private static final Pattern SYSTEM_EXIT = Pattern.compile(
            "System\\.exit\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern THREAD_SLEEP_UNCAUGHT = Pattern.compile(
            "Thread\\.sleep\\s*\\([^)]*\\)\\s*;(?!\\s*\\}\\s*catch)", Pattern.DOTALL);
    private static final Pattern HARDCODED_SECRET = Pattern.compile(
            "(password|secret|token|apiKey|api_key)\\s*=\\s*\"[^\"]{8,}\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INFINITE_LOOP = Pattern.compile(
            "while\\s*\\(\\s*true\\s*\\)");
    private static final Pattern PRINT_STACK_TRACE = Pattern.compile(
            "\\.printStackTrace\\s*\\(");

    public BugCheckTool() {
        super("bug_check");
    }

    @Override
    public String getName() { return "bug_check"; }

    @Override
    public String getDescription() {
        return "Static analysis: check code for common bugs (null safety, resource leaks, "
             + "SQL injection, concurrency issues, infinite loops, swallowed exceptions). "
             + "Input: 'code' (string) and optional 'language' (java/python/sql)";
    }

    @Override
    public Map<String, ToolInput> getInputs() {
        Map<String, ToolInput> inputs = new LinkedHashMap<>();
        inputs.put("code", new ToolInput("string", "Source code to analyze", true));
        inputs.put("language", new ToolInput("string", "Programming language", false));
        return inputs;
    }

    @Override
    public String getOutputType() { return "string"; }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.inputBuilder()
                .inputRequired("code")
                .inputProperty("code", "string", "Source code to analyze", true)
                .inputProperty("language", "string", "Programming language (java/python/sql)", false)
                .outputProperty("bugs", "array", "List of detected issues")
                .outputProperty("severity", "string", "Overall severity (LOW/MEDIUM/HIGH/CRITICAL)")
                .build();
    }

    @Override
    public Object call(Map<String, Object> arguments) {
        String code = (String) arguments.getOrDefault("code", "");
        String lang = (String) arguments.getOrDefault("language", "java");

        if (code.isEmpty()) {
            return Map.of("bugs", List.of(), "severity", "LOW",
                    "summary", "No code provided for analysis");
        }

        List<Bug> bugs = new ArrayList<>();

        // Language-agnostic checks
        checkPattern(code, "null-deref", "Potential null dereference — chained method calls without null check",
                NULL_DEREF, "MEDIUM", bugs);
        checkPattern(code, "empty-catch", "Empty catch block swallows exception silently",
                EMPTY_CATCH, "HIGH", bugs);
        checkPattern(code, "print-stack-trace", "printStackTrace() in production — use proper logging",
                PRINT_STACK_TRACE, "MEDIUM", bugs);
        checkPattern(code, "hardcoded-secret", "Hardcoded credential/secret found",
                HARDCODED_SECRET, "CRITICAL", bugs);

        // Language-specific checks
        if ("java".equalsIgnoreCase(lang)) {
            checkPattern(code, "unclosed-resource", "Resource opened but may not be closed — use try-with-resources",
                    UNCLOSED_STREAM, "HIGH", bugs);
            checkPattern(code, "system-exit", "System.exit() in application code — kills JVM abruptly",
                    SYSTEM_EXIT, "HIGH", bugs);
            checkPattern(code, "infinite-loop", "while(true) without break condition may cause infinite loop",
                    INFINITE_LOOP, "MEDIUM", bugs);
        }

        if ("sql".equalsIgnoreCase(lang) || code.toLowerCase().contains("select")) {
            checkPattern(code, "sql-injection", "String concatenation in SQL — use PreparedStatement",
                    SQL_INJECTION, "CRITICAL", bugs);
        }

        // Determine highest severity
        String maxSeverity = bugs.stream()
                .map(b -> b.severity)
                .filter(s -> s.equals("CRITICAL")).findFirst().orElse(
                        bugs.stream().map(b -> b.severity)
                                .filter(s -> s.equals("HIGH")).findFirst().orElse(
                                        bugs.stream().map(b -> b.severity)
                                                .filter(s -> s.equals("MEDIUM")).findFirst().orElse("LOW")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bugs", bugs.stream().map(Bug::toMap).toList());
        result.put("severity", maxSeverity);
        result.put("summary", formatSummary(bugs, maxSeverity));
        return result;
    }

    @Override
    public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }

    private void checkPattern(String code, String id, String description,
                               Pattern pattern, String severity, List<Bug> bugs) {
        var m = pattern.matcher(code);
        int count = 0;
        StringBuilder context = new StringBuilder();
        while (m.find() && count < 3) {
            int start = Math.max(0, m.start() - 20);
            int end = Math.min(code.length(), m.end() + 20);
            if (count > 0) context.append("; ");
            context.append("...").append(code.substring(start, end).replace("\n", " ")).append("...");
            count++;
        }
        if (count > 0) {
            bugs.add(new Bug(id, description, severity, count, context.toString()));
        }
    }

    private String formatSummary(List<Bug> bugs, String maxSeverity) {
        if (bugs.isEmpty()) return "No bugs detected. Code passes static analysis.";
        long critical = bugs.stream().filter(b -> "CRITICAL".equals(b.severity)).count();
        long high = bugs.stream().filter(b -> "HIGH".equals(b.severity)).count();
        long medium = bugs.stream().filter(b -> "MEDIUM".equals(b.severity)).count();
        return String.format("Found %d issue(s): %d CRITICAL, %d HIGH, %d MEDIUM. Severity: %s",
                bugs.size(), critical, high, medium, maxSeverity);
    }

    private record Bug(String id, String description, String severity, int occurrences, String context) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("description", description);
            m.put("severity", severity); m.put("occurrences", occurrences);
            m.put("context", context);
            return m;
        }
    }
}
