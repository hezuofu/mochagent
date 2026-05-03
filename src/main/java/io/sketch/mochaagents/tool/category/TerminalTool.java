package io.sketch.mochaagents.tool.category;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import java.util.Map;

/** @author lanxia39@163.com */
public class TerminalTool implements Tool {
    @Override public String getName() { return "terminal"; }
    @Override public String getDescription() { return "Terminal command execution"; }
    @Override public Map<String, ToolInput> getInputs() { return Map.of("command", ToolInput.string("Command to execute")); }
    @Override public String getOutputType() { return "string"; }
    @Override public Object call(Map<String, Object> args) { return "terminal output"; }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.HIGH; }
}
