package io.sketch.mochaagents.examples.tools;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;

import java.util.Map;

/**
 * 随机事实工具 — 对应 smolagents 的 get_random_fact.
 */
public final class FactTool implements Tool {
    @Override public String getName() { return "get_random_fact"; }
    @Override public String getDescription() { return "Fetches a random interesting fact."; }
    @Override public Map<String, ToolInput> getInputs() { return Map.of(); }
    @Override public String getOutputType() { return "string"; }
    @Override public Object call(Map<String, Object> args) {
        return "Random Fact: Honey never spoils. Archaeologists found 3000-year-old honey "
                + "in Egyptian tombs that was still perfectly edible.";
    }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
}
