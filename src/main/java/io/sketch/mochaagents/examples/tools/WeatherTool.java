package io.sketch.mochaagents.examples.tools;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;

import java.util.Map;

/**
 * 天气工具 — 对应 smolagents 的 get_weather.
 * @author lanxia39@163.com
 */
public final class WeatherTool implements Tool {
    @Override public String getName() { return "get_weather"; }
    @Override public String getDescription() {
        return "Get the current weather at the given location. Input: location (city name), celsius (boolean, optional).";
    }
    @Override public Map<String, ToolInput> getInputs() {
        return Map.of(
                "location", ToolInput.string("The location (city name)"),
                "celsius", ToolInput.booleanInput("Whether to return temperature in Celsius")
        );
    }
    @Override public String getOutputType() { return "string"; }
    @Override public Object call(Map<String, Object> args) {
        String location = (String) args.getOrDefault("location", "Unknown");
        boolean celsius = Boolean.TRUE.equals(args.get("celsius"));
        String temp = celsius ? "-12°C" : "10°F";
        return "The current weather in " + location + " is cloudy with torrential rains, temperature " + temp + ".";
    }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
}
