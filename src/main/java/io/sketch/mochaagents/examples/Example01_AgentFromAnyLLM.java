package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.core.impl.CodeAgent;
import io.sketch.mochaagents.core.impl.ToolCallingAgent;
import io.sketch.mochaagents.llm.provider.MockLLM;
import io.sketch.mochaagents.tool.ToolRegistry;
import io.sketch.mochaagents.examples.tools.WeatherTool;

/**
 * Example01 — 对应 smolagents 的 agent_from_any_llm.py.
 *
 * <p>演示同一个任务分别使用 ToolCallingAgent 和 CodeAgent 执行，
 * 展示两种 ReAct Agent 子类型的差异.
 *
 * <pre>
 *   smolagents 对应:
 *     agent = ToolCallingAgent(tools=[get_weather], model=model)
 *     agent.run("What's the weather like in Paris?")
 *     agent = CodeAgent(tools=[get_weather], model=model)
 *     agent.run("What's the weather like in Paris?")
 * </pre>
 */
public final class Example01_AgentFromAnyLLM {

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("Example01: AgentFromAnyLLM — ToolCallingAgent vs CodeAgent");
        System.out.println("=".repeat(60));

        var llm = LLMFactory.create();
        var registry = new ToolRegistry();
        registry.register(new WeatherTool());

        // ── ToolCallingAgent ──
        System.out.println("\n>>> ToolCallingAgent:");
        var toolAgent = ToolCallingAgent.builder()
                .name("tool-assistant")
                .llm(llm)
                .toolRegistry(registry)
                .maxSteps(3)
                .build();

        String result1 = toolAgent.run("What's the weather like in Paris?");
        System.out.println("ToolCallingAgent result: " + result1);

        // ── CodeAgent ──
        System.out.println("\n>>> CodeAgent:");
        var codeAgent = CodeAgent.builder()
                .name("code-assistant")
                .llm(llm)
                .toolRegistry(registry)
                .maxSteps(3)
                .build();

        String result2 = codeAgent.run("What's the weather like in Paris?");
        System.out.println("CodeAgent result: " + result2);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Example01 Complete.");
    }

    private Example01_AgentFromAnyLLM() {}
}
