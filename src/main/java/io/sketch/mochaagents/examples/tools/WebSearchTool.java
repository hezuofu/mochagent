package io.sketch.mochaagents.examples.tools;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;

import java.util.Map;

/**
 * Web search tool — delegates to the real DuckDuckGo-based WebSearchTool in tool/impl.
 * @author lanxia39@163.com
 */
public final class WebSearchTool implements Tool {

    private final io.sketch.mochaagents.tool.impl.WebSearchTool delegate =
            new io.sketch.mochaagents.tool.impl.WebSearchTool();

    @Override public String getName() { return "web_search"; }
    @Override public String getDescription() { return delegate.getDescription(); }
    @Override public Map<String, ToolInput> getInputs() { return delegate.getInputs(); }
    @Override public String getOutputType() { return "string"; }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
    @Override public Object call(Map<String, Object> args) { return delegate.call(args); }
}
