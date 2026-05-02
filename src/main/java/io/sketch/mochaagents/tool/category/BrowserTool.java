package io.sketch.mochaagents.tool.category;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import java.util.Map;

public class BrowserTool implements Tool {
    @Override public String getName() { return "browser"; }
    @Override public String getDescription() { return "Browser automation"; }
    @Override public Map<String, ToolInput> getInputs() { return Map.of("url", ToolInput.string("URL to navigate")); }
    @Override public String getOutputType() { return "string"; }
    @Override public Object call(Map<String, Object> args) { return "browser content"; }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.MEDIUM; }
}
