package io.sketch.mochaagents.examples.tools;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;

import java.util.Map;

/**
 * Wikipedia 搜索工具 — 对应 smolagents 的 search_wikipedia.
 * @author lanxia39@163.com
 */
public final class WikipediaTool implements Tool {
    @Override public String getName() { return "search_wikipedia"; }
    @Override public String getDescription() {
        return "Fetches a summary of a Wikipedia page for a given query.";
    }
    @Override public Map<String, ToolInput> getInputs() {
        return Map.of("query", ToolInput.string("The search term to look up on Wikipedia"));
    }
    @Override public String getOutputType() { return "string"; }
    @Override public Object call(Map<String, Object> args) {
        String query = (String) args.getOrDefault("query", "");
        return "Summary for " + query + ": " + query + " is a notable topic in its field. "
                + "It has been widely studied and referenced in academic literature. "
                + "Key developments include significant breakthroughs in recent decades.";
    }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
}
