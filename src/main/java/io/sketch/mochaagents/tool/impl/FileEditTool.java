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
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件编辑工具（search_replace）— 对齐 claude-code 的 FileEditTool.
 *
 * <p>通过精确字符串匹配实现文件局部修改，支持 replace_all 批量替换。
 * 智能处理引号风格匹配和多重出现校验。
 * @author lanxia39@163.com
 */
public class FileEditTool extends AbstractTool {

    private static final String NAME = "edit_file";

    public FileEditTool() {
        super(builder(NAME, "Edit a file using search-and-replace. "
                        + "Must read the file first. Supports exact match and replace_all mode.",
                SecurityLevel.HIGH)
                .destructive(true)
                .searchHint("modify file contents in place")
        );
    }

    // ==================== Schema ====================

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .inputType("object")
                .inputRequired("file_path", "old_string", "new_string")
                .inputProperty("file_path", "string", "The absolute path to the file to edit", true)
                .inputProperty("old_string", "string", "The text to replace", true)
                .inputProperty("new_string", "string", "The text to replace with", true)
                .inputProperty("replace_all", "boolean", "Whether to replace all occurrences", false)
                .outputType("object")
                .outputProperty("filePath", "string", "The path to the file that was edited")
                .outputProperty("oldString", "string", "The actual old string matched")
                .outputProperty("newString", "string", "The replacement string")
                .outputProperty("occurrences", "integer", "Number of replacements made")
                .build();
    }

    @Override
    public Map<String, ToolInput> getInputs() {
        Map<String, ToolInput> inputs = new LinkedHashMap<>();
        inputs.put("file_path", ToolInput.string("The absolute path to the file to edit"));
        inputs.put("old_string", ToolInput.string("The text to replace"));
        inputs.put("new_string", ToolInput.string("The text to replace with"));
        inputs.put("replace_all", new ToolInput("boolean", "Whether to replace all occurrences", true));
        return inputs;
    }

    @Override
    public String getOutputType() { return "object"; }

    // ==================== Validation ====================

    @Override
    public ValidationResult validateInput(Map<String, Object> arguments) {
        String filePath = (String) arguments.get("file_path");
        String oldString = (String) arguments.get("old_string");
        String newString = (String) arguments.get("new_string");
        boolean replaceAll = Boolean.TRUE.equals(arguments.get("replace_all"));

        if (filePath == null || filePath.isBlank()) {
            return ValidationResult.invalid("file_path is required", 1);
        }
        if (oldString == null) {
            return ValidationResult.invalid("old_string is required", 2);
        }
        if (newString == null) {
            return ValidationResult.invalid("new_string is required", 3);
        }
        if (oldString.equals(newString)) {
            return ValidationResult.invalid("No changes to make: old_string and new_string are exactly the same", 4);
        }

        Path path = Paths.get(filePath).toAbsolutePath().normalize();

        if (!Files.exists(path)) {
            // Empty old_string on non-existent file = create new file
            if (oldString.isEmpty()) return ValidationResult.valid();
            return ValidationResult.invalid("File does not exist: " + filePath, 5);
        }

        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String oldStr = findActualString(content, oldString);
            if (oldStr == null) {
                return ValidationResult.invalid("String to replace not found in file: " + oldString, 6);
            }

            int count = countOccurrences(content, oldStr);
            if (count > 1 && !replaceAll) {
                return ValidationResult.invalid("Found " + count
                        + " matches but replace_all is false. Set replace_all to true or add more context.",
                        7, Map.of("matches", count, "actualOldString", oldStr));
            }

            return ValidationResult.valid(Map.of("actualOldString", oldStr));

        } catch (IOException e) {
            return ValidationResult.invalid("Cannot read file: " + e.getMessage(), 8);
        }
    }

    // ==================== Call ====================

    @Override
    public Object call(Map<String, Object> arguments) {
        String filePath = (String) arguments.get("file_path");
        String oldString = (String) arguments.get("old_string");
        String newString = (String) arguments.get("new_string");
        boolean replaceAll = Boolean.TRUE.equals(arguments.get("replace_all"));

        Path path = Paths.get(filePath).toAbsolutePath().normalize();

        try {
            // Create parent directories
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            String content = Files.exists(path)
                    ? Files.readString(path, StandardCharsets.UTF_8) : "";

            String actualOld = findActualString(content, oldString);
            if (actualOld == null) {
                actualOld = oldString;
            }

            // Preserve quote style
            String actualNew = preserveQuoteStyle(oldString, actualOld, newString);

            // Perform replacement
            String updated;
            int occurrences;
            if (replaceAll) {
                updated = content.replace(actualOld, actualNew);
                occurrences = countOccurrences(content, actualOld);
            } else {
                updated = content.replaceFirst(java.util.regex.Pattern.quote(actualOld),
                        java.util.regex.Matcher.quoteReplacement(actualNew));
                occurrences = 1;
            }

            // Write back
            Files.writeString(path, updated, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("filePath", filePath);
            result.put("oldString", actualOld);
            result.put("newString", newString);
            result.put("occurrences", occurrences);
            return result;

        } catch (IOException e) {
            throw new RuntimeException("Failed to edit file: " + filePath, e);
        }
    }

    // ==================== Result Formatting ====================

    @Override
    public String formatResult(Object output, String toolUseId) {
        if (!(output instanceof Map)) return output != null ? output.toString() : "";

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) output;
        String fp = (String) map.get("filePath");
        int occurrences = ((Number) map.getOrDefault("occurrences", 1)).intValue();

        if (occurrences > 1) {
            return "The file " + fp + " has been updated. All " + occurrences + " occurrences were successfully replaced.";
        }
        return "The file " + fp + " has been updated successfully.";
    }

    // ==================== String Matching ====================

    /**
     * Find the actual string in content, handling smart quote normalization.
     * Tries exact match first, then curvy quote variants.
     */
    static String findActualString(String content, String search) {
        if (content.contains(search)) return search;

        // Try normalized quote variants
        String normalized = search
                .replace('\u201C', '"')  // left double quote
                .replace('\u201D', '"')  // right double quote
                .replace('\u2018', '\'') // left single quote
                .replace('\u2019', '\''); // right single quote
        if (!normalized.equals(search) && content.contains(normalized)) {
            return normalized;
        }

        // Try curvy → straight in the other direction
        String curvy = search
                .replace('"', '\u201C');
        // Only match if content actually has curvy quotes
        if (content.contains("\u201C") && content.contains(curvy)) {
            return curvy;
        }

        return null;
    }

    /**
     * Preserve quote style from the actual match found in the file.
     */
    static String preserveQuoteStyle(String original, String actual, String replacement) {
        if (original.equals(actual)) return replacement;

        // Map the quote styles from actual → replacement
        String style = actual;
        String result = replacement;

        if (style.contains("\u201C") && !replacement.contains("\u201C")) {
            result = result.replace("\"", "\u201C");
        }
        if (style.contains("\u201D") && !replacement.contains("\u201D")) {
            result = result.replace("\"", "\u201D");
        }

        return result;
    }

    private static int countOccurrences(String content, String search) {
        if (search.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(search, idx)) >= 0) {
            count++;
            idx += search.length();
        }
        return count;
    }
}
