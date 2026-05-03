package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.agent.impl.CodeAgent;
import io.sketch.mochaagents.tool.ToolRegistry;
import io.sketch.mochaagents.examples.tools.*;

/**
 * Example02 — 对应 smolagents 的 multiple_tools.py.
 *
 * <p>构建一个带 6 个工具的 CodeAgent，展示多工具协同工作能力.
 *
 * <pre>
 *   smolagents 对应:
 *     agent = CodeAgent(tools=[
 *         convert_currency, get_weather, get_news_headlines,
 *         get_joke, get_random_fact, search_wikipedia
 *     ], model=model)
 *     agent.run("Convert 5000 dollars to Euros")
 *     agent.run("What is the weather in New York?")
 *     agent.run("Give me the top news headlines")
 *     agent.run("Tell me a joke")
 *     agent.run("Tell me a Random Fact")
 *     agent.run("who is Elon Musk?")
 * </pre>
 * @author lanxia39@163.com
 */
public final class Example02_MultipleTools {

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("Example02: MultipleTools — 6 工具联动 CodeAgent");
        System.out.println("=".repeat(60));

        var registry = new ToolRegistry();
        registry.register(new CurrencyTool());
        registry.register(new WeatherTool());
        registry.register(new NewsTool());
        registry.register(new JokeTool());
        registry.register(new FactTool());
        registry.register(new WikipediaTool());

        String[] queries = {
                "Convert 5000 dollars to Euros",
                "What is the weather in New York?",
                "Give me the top news headlines",
                "Tell me a joke",
                "Tell me a Random Fact",
                "who is Elon Musk?",
        };

        for (String query : queries) {
            System.out.println("\n── Query: " + query);

            // 每个 query 独立 MockLLM（任务感知）
            var agent = CodeAgent.builder()
                    .name("multi-tool-agent")
                    .llm(LLMFactory.create())
                    .toolRegistry(registry)
                    .maxSteps(3)
                    .build();

            try {
                String result = agent.run(query);
                System.out.println("Result: " + result);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Example02 Complete.");
    }

    private Example02_MultipleTools() {}
}
