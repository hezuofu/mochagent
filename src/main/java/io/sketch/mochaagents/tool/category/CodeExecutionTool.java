package io.sketch.mochaagents.tool.category;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import java.util.Map;

/** @author lanxia39@163.com */
public class CodeExecutionTool implements Tool {
    @Override public String getName() { return "code_execution"; }
    @Override public String getDescription() { return "Execute code in sandbox"; }
    @Override public Map<String, ToolInput> getInputs() { return Map.of("code", ToolInput.string("Code to execute"), "language", ToolInput.string("Programming language")); }
    @Override public String getOutputType() { return "string"; }
    @Override public Object call(Map<String, Object> args) { return "code output"; }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.CRITICAL; }
}
