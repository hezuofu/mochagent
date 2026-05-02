package io.sketch.mochaagents.tool.category;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import java.util.Map;

public class SearchTool implements Tool {
    @Override public String getName() { return "search"; }
    @Override public String getDescription() { return "Web search"; }
    @Override public Map<String, ToolInput> getInputs() { return Map.of("query", ToolInput.string("Search query")); }
    @Override public String getOutputType() { return "string"; }
    @Override public Object call(Map<String, Object> args) { return "search results"; }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.MEDIUM; }
}
