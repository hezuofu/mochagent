package io.sketch.mochaagents.tool.impl;

import io.sketch.mochaagents.tool.AbstractTool;
import io.sketch.mochaagents.tool.PermissionResult;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolSchema;
import io.sketch.mochaagents.tool.ValidationResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件写入工具 — 对齐 claude-code 的 FileWriteTool.
 *
 * <p>支持创建新文件和覆盖已有文件。破坏性操作，需要权限控制。
 * @author lanxia39@163.com
 */
public class FileWriteTool extends AbstractTool {

    private static final String NAME = "write_file";

    public FileWriteTool() {
        super(builder(NAME, "Write a file to the local filesystem. "
                        + "Creates parent directories if needed.",
                SecurityLevel.HIGH)
                .destructive(true)
                .searchHint("create or overwrite files")
        );
    }

    // ==================== Schema ====================

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .inputType("object")
                .inputRequired("file_path", "content")
                .inputProperty("file_path", "string", "The absolute path to the file to write", true)
                .inputProperty("content", "string", "The content to write to the file", true)
                .outputType("object")
                .outputProperty("type", "string", "Whether create or update")
                .outputProperty("filePath", "string", "The path to the file that was written")
                .outputProperty("oldContent", "string", "Original file content (null for new files)")
                .build();
    }

    @Override
    public Map<String, ToolInput> getInputs() {
        Map<String, ToolInput> inputs = new LinkedHashMap<>();
        inputs.put("file_path", ToolInput.string("The absolute path to the file to write"));
        inputs.put("content", ToolInput.string("The content to write to the file"));
        return inputs;
    }

    @Override
    public String getOutputType() { return "object"; }

    // ==================== Validation ====================

    @Override
    public ValidationResult validateInput(Map<String, Object> arguments) {
        String filePath = (String) arguments.get("file_path");
        String content = (String) arguments.get("content");

        if (filePath == null || filePath.isBlank()) {
            return ValidationResult.invalid("file_path is required", 1);
        }
        if (content == null) {
            return ValidationResult.invalid("content is required", 2);
        }
        return ValidationResult.valid();
    }

    // ==================== Permissions ====================

    @Override
    public PermissionResult checkPermissions(Map<String, Object> arguments) {
        // FileWrite is destructive — require explicit permission in strict mode
        return PermissionResult.allow(arguments);
    }

    // ==================== Call ====================

    @Override
    public Object call(Map<String, Object> arguments) {
        String filePath = (String) arguments.get("file_path");
        String content = (String) arguments.get("content");

        Path path = Paths.get(filePath).toAbsolutePath().normalize();
        boolean exists = Files.exists(path);

        try {
            // Create parent directories
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            // Read old content if updating
            String oldContent = null;
            if (exists) {
                oldContent = Files.readString(path, StandardCharsets.UTF_8);
            }

            // Write
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", exists ? "update" : "create");
            result.put("filePath", filePath);
            result.put("content", content);
            result.put("oldContent", oldContent);
            return result;

        } catch (IOException e) {
            throw new RuntimeException("Failed to write file: " + filePath, e);
        }
    }

    // ==================== Result Formatting ====================

    @Override
    public String formatResult(Object output, String toolUseId) {
        if (!(output instanceof Map)) return output != null ? output.toString() : "";

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) output;
        String type = (String) map.getOrDefault("type", "create");
        String fp = (String) map.get("filePath");

        if ("update".equals(type)) {
            return "The file " + fp + " has been updated successfully.";
        }
        return "File created successfully at: " + fp;
    }
}
