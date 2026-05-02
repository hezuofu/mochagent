package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.core.impl.CodeAgent;
import io.sketch.mochaagents.llm.provider.MockLLM;
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolRegistry;

import java.util.Map;

/**
 * Example06 — 对应 smolagents 的 structured_output_tool.py.
 *
 * <p>演示结构化输出工具 — 工具返回 WeatherInfo 对象而非纯字符串.
 *
 * <pre>
 *   smolagents 对应:
 *     class WeatherInfo(BaseModel):
 *         location: str
 *         temperature: float
 *         conditions: str
 *         humidity: int
 *     def get_weather_info(city: str) -> WeatherInfo
 * </pre>
 */
public final class Example06_StructuredOutput {

    /** 结构化天气信息. */
    public record WeatherInfo(String location, double temperature, String conditions, int humidity) {
        @Override
        public String toString() {
            return String.format(
                    "WeatherInfo[location=%s, temperature=%.1f°C, conditions=%s, humidity=%d%%]",
                    location, temperature, conditions, humidity);
        }
    }

    /** 结构化输出天气工具 */
    private static final class StructuredWeatherTool implements Tool {
        @Override public String getName() { return "get_weather_info"; }
        @Override public String getDescription() {
            return "Get weather information for a location as structured data.";
        }
        @Override public Map<String, ToolInput> getInputs() {
            return Map.of("city", ToolInput.string("The city name"));
        }
        @Override public String getOutputType() { return "WeatherInfo"; }
        @Override public Object call(Map<String, Object> args) {
            String city = String.valueOf(args.getOrDefault("city", "Unknown"));
            return new WeatherInfo(city, 22.5, "partly cloudy", 65);
        }
        @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("Example06: StructuredOutput — 结构化输出工具");
        System.out.println("=".repeat(60));

        var registry = new ToolRegistry();
        registry.register(new StructuredWeatherTool());

        // MockLLM 识别 temperature → 触发 weather 分支
        var agent = CodeAgent.builder()
                .name("weather-agent")
                .llm(LLMFactory.create())
                .toolRegistry(registry)
                .maxSteps(3)
                .build();

        System.out.println("\nQuery: What is the temperature in Tokyo in Fahrenheit?");
        String result = agent.run("What is the temperature in Tokyo in Fahrenheit?");
        System.out.println("Result: " + result);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Example06 Complete.");
    }

    private Example06_StructuredOutput() {}
}
