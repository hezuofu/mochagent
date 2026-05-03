package io.sketch.mochaagents.examples.tools;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;

import java.util.Map;

/**
 * 新闻工具 — 对应 smolagents 的 get_news_headlines.
 * @author lanxia39@163.com
 */
public final class NewsTool implements Tool {
    @Override public String getName() { return "get_news_headlines"; }
    @Override public String getDescription() { return "Fetches the top news headlines."; }
    @Override public Map<String, ToolInput> getInputs() { return Map.of(); }
    @Override public String getOutputType() { return "string"; }
    @Override public Object call(Map<String, Object> args) {
        return "1. Global AI Summit Reaches New Agreement - Reuters\n"
                + "2. Stock Markets Hit Record Highs - Bloomberg\n"
                + "3. Climate Conference Results in Historic Pact - BBC\n"
                + "4. Tech Giants Announce Open Source Alliance - TechCrunch\n"
                + "5. Space Exploration Milestone Achieved - NASA";
    }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
}
