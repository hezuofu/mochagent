package io.sketch.mochaagents.tool.impl;

import io.sketch.mochaagents.tool.AbstractTool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolSchema;
import io.sketch.mochaagents.tool.ValidationResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Grep 内容搜索工具 — 对齐 claude-code 的 GrepTool.
 *
 * <p>使用 Java Regex 实现文件内容搜索，支持 content/files_with_matches/count 三种模式，
 * 上下文行、大小写不敏感、glob 过滤、分页。
 * @author lanxia39@163.com
 */
public class GrepTool extends AbstractTool {

    private static final String NAME = "grep";
    private static final int DEFAULT_HEAD_LIMIT = 250;
    private static final int DEFAULT_OFFSET = 0;
    private static final Set<String> VCS_DIRS = Set.of(".git", ".svn", ".hg", ".bzr");

    public GrepTool() {
        super(builder(NAME, "Search file contents using regular expressions. "
                        + "Returns matching lines with context.",
                SecurityLevel.LOW)
                .readOnly(true)
                .concurrencySafe(true)
                .searchHint("search file contents with regex")
        );
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .inputType("object")
                .inputRequired("pattern")
                .inputProperty("pattern", "string", "The regex pattern to search for", true)
                .inputProperty("path", "string", "File or directory to search in", false)
                .inputProperty("glob", "string", "Glob pattern to filter files", false)
                .inputProperty("output_mode", "string", "Output mode: content, files_with_matches, count", false)
                .inputProperty("-A", "integer", "Lines after match (context)", false)
                .inputProperty("-B", "integer", "Lines before match (context)", false)
                .inputProperty("-C", "integer", "Lines before and after match (context)", false)
                .inputProperty("-i", "boolean", "Case insensitive search", false)
                .inputProperty("-n", "boolean", "Show line numbers", false)
                .inputProperty("head_limit", "integer", "Limit output lines", false)
                .inputProperty("offset", "integer", "Skip first N lines", false)
                .outputType("object")
                .outputProperty("mode", "string", "Output mode used")
                .outputProperty("numFiles", "integer", "Number of matching files")
                .outputProperty("filenames", "array", "Matching file paths")
                .outputProperty("content", "string", "Matching content lines")
                .build();
    }

    @Override
    public Map<String, ToolInput> getInputs() {
        Map<String, ToolInput> inputs = new LinkedHashMap<>();
        inputs.put("pattern", ToolInput.string("The regex pattern to search for"));
        inputs.put("path", new ToolInput("string", "File or directory to search in", true));
        inputs.put("glob", new ToolInput("string", "Glob pattern to filter files", true));
        inputs.put("output_mode", new ToolInput("string", "Output mode: content, files_with_matches, count", true));
        inputs.put("-i", new ToolInput("boolean", "Case insensitive search", true));
        inputs.put("-n", new ToolInput("boolean", "Show line numbers", true));
        inputs.put("head_limit", new ToolInput("integer", "Limit output lines", true));
        return inputs;
    }

    @Override
    public String getOutputType() { return "object"; }

    @Override
    public ValidationResult validateInput(Map<String, Object> arguments) {
        String pattern = (String) arguments.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return ValidationResult.invalid("pattern is required", 1);
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return ValidationResult.invalid("Invalid regex pattern: " + e.getMessage(), 2);
        }
        return ValidationResult.valid();
    }

    @Override
    public Object call(Map<String, Object> arguments) {
        String patternStr = (String) arguments.get("pattern");
        String pathStr = (String) arguments.get("path");
        String globStr = (String) arguments.get("glob");
        String outputMode = (String) arguments.getOrDefault("output_mode", "files_with_matches");
        int contextBefore = getIntArg(arguments, "-B", 0);
        int contextAfter = getIntArg(arguments, "-A", 0);
        int contextBoth = getIntArg(arguments, "-C", 0);
        boolean caseInsensitive = Boolean.TRUE.equals(arguments.get("-i"));
        boolean showLineNumbers = !Boolean.FALSE.equals(arguments.get("-n"));
        int headLimit = getIntArg(arguments, "head_limit", DEFAULT_HEAD_LIMIT);
        int offset = getIntArg(arguments, "offset", DEFAULT_OFFSET);

        // Resolve context
        if (contextBoth > 0) {
            contextBefore = contextBoth;
            contextAfter = contextBoth;
        }

        Path searchPath = (pathStr != null && !pathStr.isBlank())
                ? Paths.get(pathStr).toAbsolutePath().normalize()
                : Paths.get("").toAbsolutePath();

        int flags = caseInsensitive ? Pattern.CASE_INSENSITIVE : 0;
        Pattern regex = Pattern.compile(patternStr, flags);

        try {
            switch (outputMode) {
                case "content":
                    return searchContent(searchPath, regex, globStr, contextBefore, contextAfter,
                            showLineNumbers, headLimit, offset);
                case "count":
                    return searchCount(searchPath, regex, globStr, headLimit, offset);
                default:
                    return searchFilesWithMatches(searchPath, regex, globStr, headLimit, offset);
            }
        } catch (IOException e) {
            throw new RuntimeException("Grep search failed: " + e.getMessage(), e);
        }
    }

    // ---- Search Modes ----

    private Map<String, Object> searchContent(Path root, Pattern regex, String glob,
                                               int before, int after, boolean showLineNumbers,
                                               int headLimit, int offset) throws IOException {
        List<String> results = new ArrayList<>();
        List<String> allFiles = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isVcsDir(p))
                    .filter(p -> matchesGlob(p, glob))
                    .toList();

            for (Path file : files) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (regex.matcher(lines.get(i)).find()) {
                        allFiles.add(root.relativize(file).toString().replace('\\', '/'));
                        int start = Math.max(0, i - before);
                        int end = Math.min(lines.size(), i + after + 1);
                        for (int j = start; j < end; j++) {
                            String prefix = showLineNumbers ? (j + 1) + ":" : "";
                            String marker = j == i ? ":" : "-";
                            results.add(prefix + marker + lines.get(j));
                        }
                        results.add("--");
                    }
                }
            }
        }

        // Apply pagination
        int from = Math.min(offset, results.size());
        int to = headLimit == 0 ? results.size() : Math.min(from + headLimit, results.size());
        List<String> page = results.subList(from, to);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "content");
        result.put("numFiles", allFiles.size());
        result.put("filenames", allFiles.stream().distinct().toList());
        result.put("content", String.join("\n", page));
        result.put("numLines", page.size());
        if (headLimit > 0 && results.size() - offset > headLimit) {
            result.put("appliedLimit", headLimit);
            result.put("appliedOffset", offset);
        }
        return result;
    }

    private Map<String, Object> searchFilesWithMatches(Path root, Pattern regex, String glob,
                                                        int headLimit, int offset) throws IOException {
        List<String> matches = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isVcsDir(p))
                    .filter(p -> matchesGlob(p, glob))
                    .toList();

            for (Path file : files) {
                try {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    if (regex.matcher(content).find()) {
                        matches.add(root.relativize(file).toString().replace('\\', '/'));
                    }
                } catch (IOException ignored) {
                    // Skip unreadable files
                }
            }
        }

        // Sort and paginate
        matches.sort(String::compareTo);
        int from = Math.min(offset, matches.size());
        int to = headLimit == 0 ? matches.size() : Math.min(from + headLimit, matches.size());
        List<String> page = matches.subList(from, to);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "files_with_matches");
        result.put("numFiles", page.size());
        result.put("filenames", page);
        if (headLimit > 0 && matches.size() - offset > headLimit) {
            result.put("appliedLimit", headLimit);
            result.put("appliedOffset", offset);
        }
        return result;
    }

    private Map<String, Object> searchCount(Path root, Pattern regex, String glob,
                                             int headLimit, int offset) throws IOException {
        Map<String, Integer> countMap = new LinkedHashMap<>();

        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isVcsDir(p))
                    .filter(p -> matchesGlob(p, glob))
                    .toList();

            for (Path file : files) {
                try {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    Matcher m = regex.matcher(content);
                    int count = 0;
                    while (m.find()) count++;
                    if (count > 0) {
                        countMap.put(root.relativize(file).toString().replace('\\', '/'), count);
                    }
                } catch (IOException ignored) {}
            }
        }

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(countMap.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        int from = Math.min(offset, entries.size());
        int to = headLimit == 0 ? entries.size() : Math.min(from + headLimit, entries.size());
        List<Map.Entry<String, Integer>> page = entries.subList(from, to);

        int totalMatches = page.stream().mapToInt(Map.Entry::getValue).sum();
        List<String> lines = page.stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "count");
        result.put("numFiles", page.size());
        result.put("filenames", page.stream().map(Map.Entry::getKey).toList());
        result.put("content", String.join("\n", lines));
        result.put("numMatches", totalMatches);
        if (headLimit > 0 && entries.size() - offset > headLimit) {
            result.put("appliedLimit", headLimit);
            result.put("appliedOffset", offset);
        }
        return result;
    }

    // ---- Helpers ----

    private static boolean isVcsDir(Path path) {
        for (int i = 0; i < path.getNameCount(); i++) {
            if (VCS_DIRS.contains(path.getName(i).toString())) return true;
        }
        return false;
    }

    private static boolean matchesGlob(Path path, String glob) {
        if (glob == null || glob.isBlank()) return true;
        String syntax = "glob:" + glob;
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher(syntax);
            return matcher.matches(path.getFileName());
        } catch (Exception e) {
            return true; // Invalid glob → don't filter
        }
    }

    private static int getIntArg(Map<String, Object> args, String key, int defaultVal) {
        Object v = args.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String s && !s.isEmpty()) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    @Override
    public String formatResult(Object output, String toolUseId) {
        if (!(output instanceof Map)) return output != null ? output.toString() : "";
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) output;
        String mode = (String) map.getOrDefault("mode", "files_with_matches");
        String content = (String) map.get("content");
        int numFiles = ((Number) map.getOrDefault("numFiles", 0)).intValue();

        if ("content".equals(mode)) {
            if (content == null || content.isEmpty()) return "No matches found";
            return content;
        }

        if ("count".equals(mode)) {
            int numMatches = ((Number) map.getOrDefault("numMatches", 0)).intValue();
            return (content != null ? content : "No matches found")
                    + "\n\nFound " + numMatches + " occurrences across " + numFiles + " files.";
        }

        if (numFiles == 0) return "No files found";
        @SuppressWarnings("unchecked")
        List<String> filenames = (List<String>) map.getOrDefault("filenames", List.of());
        return "Found " + numFiles + " file(s)\n" + String.join("\n", filenames);
    }
}
