package io.sketch.mochaagents.tool.impl;

import io.sketch.mochaagents.tool.AbstractTool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolSchema;
import io.sketch.mochaagents.tool.ValidationResult;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Glob 文件匹配工具 — 对齐 claude-code 的 GlobTool.
 *
 * <p>支持通配符模式匹配文件名，返回相对路径列表。只读、并发安全。
 */
public class GlobTool extends AbstractTool {

    private static final String NAME = "glob";
    private static final int DEFAULT_MAX_RESULTS = 100;

    public GlobTool() {
        super(builder(NAME, "Find files matching a glob pattern. "
                        + "Returns relative file paths for easy reference.",
                SecurityLevel.LOW)
                .readOnly(true)
                .concurrencySafe(true)
                .searchHint("find files by name pattern or wildcard")
        );
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .inputType("object")
                .inputRequired("pattern")
                .inputProperty("pattern", "string", "The glob pattern to match files against", true)
                .inputProperty("path", "string", "Directory to search in. Defaults to current working directory", false)
                .outputType("object")
                .outputProperty("filenames", "array", "Array of file paths that match the pattern")
                .outputProperty("numFiles", "integer", "Total number of files found")
                .outputProperty("truncated", "boolean", "Whether results were truncated")
                .outputProperty("durationMs", "integer", "Time taken to execute in milliseconds")
                .build();
    }

    @Override
    public Map<String, ToolInput> getInputs() {
        Map<String, ToolInput> inputs = new LinkedHashMap<>();
        inputs.put("pattern", ToolInput.string("The glob pattern to match files against"));
        inputs.put("path", new ToolInput("string", "Directory to search in", true));
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
        String pathStr = (String) arguments.get("path");
        if (pathStr != null && !pathStr.isBlank()) {
            Path dir = Paths.get(pathStr);
            if (!Files.isDirectory(dir)) {
                return ValidationResult.invalid("Path is not a directory: " + pathStr, 2);
            }
        }
        return ValidationResult.valid();
    }

    @Override
    public Object call(Map<String, Object> arguments) {
        long start = System.currentTimeMillis();
        String pattern = (String) arguments.get("pattern");
        String pathStr = (String) arguments.get("path");

        Path searchDir;
        if (pathStr != null && !pathStr.isBlank()) {
            searchDir = Paths.get(pathStr).toAbsolutePath().normalize();
        } else {
            searchDir = Paths.get("").toAbsolutePath();
        }

        try {
            List<String> matches = new ArrayList<>();

            // Convert glob pattern to PathMatcher syntax
            String syntaxPattern = "glob:" + pattern;
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher(syntaxPattern);

            Files.walkFileTree(searchDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matcher.matches(file.getFileName())) {
                        Path relative = searchDir.relativize(file);
                        matches.add(relative.toString().replace('\\', '/'));
                    }
                    if (matches.size() >= DEFAULT_MAX_RESULTS) {
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
            });

            boolean truncated = matches.size() >= DEFAULT_MAX_RESULTS;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("filenames", matches);
            result.put("numFiles", matches.size());
            result.put("truncated", truncated);
            result.put("durationMs", System.currentTimeMillis() - start);
            return result;

        } catch (IOException e) {
            throw new RuntimeException("Glob search failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String formatResult(Object output, String toolUseId) {
        if (!(output instanceof Map)) return output != null ? output.toString() : "";
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) output;
        @SuppressWarnings("unchecked")
        List<String> filenames = (List<String>) map.getOrDefault("filenames", List.of());
        boolean truncated = Boolean.TRUE.equals(map.get("truncated"));

        if (filenames.isEmpty()) return "No files found";

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(filenames.size()).append(" files");
        if (truncated) sb.append(" (truncated)");
        sb.append("\n");
        sb.append(String.join("\n", filenames));
        if (truncated) sb.append("\n(Results are truncated. Consider using a more specific path or pattern.)");
        return sb.toString();
    }
}
