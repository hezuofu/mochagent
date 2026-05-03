package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.agent.impl.CodeAgent;
import io.sketch.mochaagents.agent.impl.ToolCallingAgent;
import io.sketch.mochaagents.tool.ToolRegistry;
import io.sketch.mochaagents.examples.tools.WeatherTool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Example10 — 对应 smolagents 的 async_agent/main.py.
 *
 * <p>演示异步 Agent 执行 — 使用 CompletableFuture 并行处理多个任务.
 *
 * <pre>
 *   smolagents 对应:
 *     result = await anyio.to_thread.run_sync(agent.run, task)
 *     # Starlette endpoint: async def run_agent_endpoint(request)
 * </pre>
 * @author lanxia39@163.com
 */
public final class Example10_AsyncAgent {

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(60));
        System.out.println("Example10: AsyncAgent — 异步并行 Agent 执行");
        System.out.println("=".repeat(60));

        var llm = LLMFactory.create();
        var registry = new ToolRegistry();
        registry.register(new WeatherTool());

        // 创建单个 Agent 实例
        // Python smolagents uses CodeAgent for async example; match behavior
        var agent = CodeAgent.builder()
                .name("async-weather")
                .llm(llm)
                .toolRegistry(registry)
                .maxSteps(3)
                .build();

        // ── 1. 单任务异步执行 ──
        System.out.println("\n── 1. 单任务异步 ──");
        CompletableFuture<String> future1 = agent.executeAsync("What's the weather in Paris?");
        System.out.println("Task submitted (non-blocking)...");
        System.out.println("Result: " + future1.get());

        // ── 2. 并行执行多个任务 ──
        System.out.println("\n── 2. 并行多任务 ──");
        String[] cities = {"What's the weather in Paris?",
                "What's the weather in New York?",
                "What's the weather in Tokyo?"};

        List<CompletableFuture<String>> futures = new ArrayList<>();
        long start = System.currentTimeMillis();

        for (String city : cities) {
            var cityAgent = CodeAgent.builder()
                    .name("weather-" + city.hashCode())
                    .llm(LLMFactory.create())
                    .toolRegistry(registry)
                    .maxSteps(3)
                    .build();
            futures.add(cityAgent.executeAsync(city));
        }

        // 等待所有完成
        CompletableFuture<Void> allDone = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));

        allDone.thenRun(() -> {
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("All " + futures.size() + " tasks completed in " + elapsed + "ms");
            for (int i = 0; i < futures.size(); i++) {
                try {
                    System.out.println("  [" + cities[i] + "] → " +
                            futures.get(i).get().substring(0, Math.min(60,
                                    futures.get(i).get().length())) + "...");
                } catch (Exception ignored) {}
            }
        }).get();

        // ── 3. 组合链式异步 ──
        System.out.println("\n── 3. 链式组合 ──");
        var chainAgent = CodeAgent.builder()
                .name("chain-agent")
                .llm(LLMFactory.create())
                .toolRegistry(registry)
                .maxSteps(3)
                .build();

        chainAgent.executeAsync("What's the weather in London?")
                .thenApply(result -> "🌤 Weather summary: " + result)
                .thenAccept(System.out::println)
                .get();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Example10 Complete.");
    }

    private Example10_AsyncAgent() {}
}
