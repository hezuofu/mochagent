package io.sketch.mochaagents.examples.tools;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;

import java.util.Map;

/**
 * 笑话工具 — 对应 smolagents 的 get_joke.
 */
public final class JokeTool implements Tool {
    @Override public String getName() { return "get_joke"; }
    @Override public String getDescription() { return "Fetches a random joke."; }
    @Override public Map<String, ToolInput> getInputs() { return Map.of(); }
    @Override public String getOutputType() { return "string"; }
    @Override public Object call(Map<String, Object> args) {
        return "Why do Java developers wear glasses? Because they don't C#!";
    }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
}
