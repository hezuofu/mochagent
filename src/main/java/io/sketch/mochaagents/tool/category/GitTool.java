package io.sketch.mochaagents.tool.category;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import java.util.Map;

public class GitTool implements Tool {
    @Override public String getName() { return "git"; }
    @Override public String getDescription() { return "Git operations"; }
    @Override public Map<String, ToolInput> getInputs() { return Map.of("command", ToolInput.string("Git command")); }
    @Override public String getOutputType() { return "string"; }
    @Override public Object call(Map<String, Object> args) { return "git result"; }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.HIGH; }
}
