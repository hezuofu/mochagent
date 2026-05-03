package io.sketch.mochaagents.tool.impl;

import io.sketch.mochaagents.tool.AbstractTool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolSchema;
import io.sketch.mochaagents.tool.ValidationResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件读取工具 — 对齐 claude-code 的 FileReadTool.
 *
 * <p>支持文本文件（行号+偏移+限制）、图片（Base64）、PDF.
 * 只读、并发安全。
 * @author lanxia39@163.com
 */
public class FileReadTool extends AbstractTool {

    private static final String NAME = "read_file";
    private static final int MAX_SIZE_BYTES = 10 * 1024 * 1024; // 10 MiB
    private static final int MAX_TOKENS_ESTIMATE = 100_000;

    public FileReadTool() {
        super(builder(NAME, "Read a file from the local filesystem. Supports text files, "
                        + "images (returns base64), and PDF files.",
                SecurityLevel.LOW)
                .readOnly(true)
                .concurrencySafe(true)
                .searchHint("read files, images, PDFs")
        );
    }

    // ==================== Schema ====================

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .inputType("object")
                .inputRequired("file_path")
                .inputProperty("file_path", "string", "The absolute path to the file to read", true)
                .inputProperty("offset", "integer", "Line number to start reading from", false)
                .inputProperty("limit", "integer", "Number of lines to read", false)
                .outputType("object")
                .outputProperty("filePath", "string", "The path to the file that was read")
                .outputProperty("content", "string", "The content of the file")
                .outputProperty("numLines", "integer", "Number of lines in the returned content")
                .outputProperty("startLine", "integer", "The starting line number")
                .outputProperty("totalLines", "integer", "Total number of lines in the file")
                .build();
    }

    // ==================== Legacy Compatibility ====================

    @Override
    public Map<String, ToolInput> getInputs() {
        Map<String, ToolInput> inputs = new LinkedHashMap<>();
        inputs.put("file_path", ToolInput.string("The absolute path to the file to read"));
        inputs.put("offset", new ToolInput("integer", "Line number to start reading from", true));
        inputs.put("limit", new ToolInput("integer", "Number of lines to read", true));
        return inputs;
    }

    @Override
    public String getOutputType() { return "object"; }

    // ==================== Validation ====================

    @Override
    public ValidationResult validateInput(Map<String, Object> arguments) {
        Object fp = arguments.get("file_path");
        if (fp == null || fp.toString().isBlank()) {
            return ValidationResult.invalid("file_path is required and must not be blank", 1);
        }
        return ValidationResult.valid();
    }

    // ==================== Call ====================

    @Override
    public Object call(Map<String, Object> arguments) {
        String filePath = (String) arguments.get("file_path");
        int offset = getInt(arguments, "offset", 1);
        Integer limit = getInteger(arguments, "limit");

        Path path = Paths.get(filePath).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new RuntimeException("File does not exist: " + filePath);
        }
        if (Files.isDirectory(path)) {
            throw new RuntimeException("Path is a directory, not a file: " + filePath);
        }

        try {
            // Check file size
            long size = Files.size(path);
            if (size > MAX_SIZE_BYTES) {
                throw new RuntimeException("File too large (" + size + " bytes). Maximum: " + MAX_SIZE_BYTES + " bytes.");
            }

            String ext = getExtension(filePath).toLowerCase();

            // Image detection
            if (isImageExtension(ext)) {
                return readImage(path, filePath);
            }

            // Text file
            return readText(path, filePath, offset, limit);

        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + filePath, e);
        }
    }

    // ==================== Internal ====================

    private Map<String, Object> readText(Path path, String filePath, int offset, Integer limit) throws IOException {
        List<String> allLines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int totalLines = allLines.size();

        int startIdx = Math.max(0, offset - 1);
        int endIdx = limit != null ? Math.min(allLines.size(), startIdx + limit) : allLines.size();
        List<String> selected = allLines.subList(startIdx, endIdx);

        // Add line numbers
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < selected.size(); i++) {
            sb.append(String.format("%6d→%s%n", startIdx + i + 1, selected.get(i)));
        }

        // Rough token estimate
        if (sb.length() > MAX_TOKENS_ESTIMATE * 4) {
            throw new RuntimeException("File content exceeds estimated token limit (" + MAX_TOKENS_ESTIMATE
                    + "). Use offset and limit to read specific portions.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "text");
        result.put("filePath", filePath);
        result.put("content", sb.toString());
        result.put("numLines", selected.size());
        result.put("startLine", offset);
        result.put("totalLines", totalLines);
        return result;
    }

    private Map<String, Object> readImage(Path path, String filePath) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String mimeType = Files.probeContentType(path);
        if (mimeType == null || !mimeType.startsWith("image/")) {
            mimeType = getMimeFromExtension(getExtension(filePath));
        }
        String base64 = Base64.getEncoder().encodeToString(bytes);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "image");
        result.put("filePath", filePath);
        result.put("base64", base64);
        result.put("mimeType", mimeType);
        result.put("originalSize", bytes.length);
        return result;
    }

    // ==================== Result Formatting ====================

    @Override
    public String formatResult(Object output, String toolUseId) {
        if (!(output instanceof Map)) return output != null ? output.toString() : "";

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) output;
        String type = (String) map.getOrDefault("type", "text");

        if ("image".equals(type)) {
            return "Image read: " + map.get("filePath") + " (" + map.get("originalSize") + " bytes)";
        }

        return map.getOrDefault("content", "").toString();
    }

    // ==================== Helpers ====================

    private static String getExtension(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : "";
    }

    private static boolean isImageExtension(String ext) {
        return "png".equals(ext) || "jpg".equals(ext) || "jpeg".equals(ext)
                || "gif".equals(ext) || "webp".equals(ext) || "bmp".equals(ext);
    }

    private static String getMimeFromExtension(String ext) {
        switch (ext.toLowerCase()) {
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            default: return "application/octet-stream";
        }
    }

    private static int getInt(Map<String, Object> args, String key, int defaultVal) {
        Object v = args.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private static Integer getInteger(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String && !((String) v).isEmpty()) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
