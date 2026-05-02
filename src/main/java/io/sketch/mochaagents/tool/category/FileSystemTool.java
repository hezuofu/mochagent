package io.sketch.mochaagents.tool.category;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import java.util.Map;

/**
 * 文件系统工具.
 */
public class FileSystemTool implements Tool {
    @Override public String getName() { return "filesystem"; }
    @Override public String getDescription() { return "File system operations"; }
    @Override public Map<String, ToolInput> getInputs() { return Map.of("path", ToolInput.string("File path"), "content", ToolInput.any("File content")); }
    @Override public String getOutputType() { return "string"; }
    @Override public Object call(Map<String, Object> args) { return "fs result"; }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.HIGH; }
}
