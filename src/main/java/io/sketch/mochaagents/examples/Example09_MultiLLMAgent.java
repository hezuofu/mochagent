package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.agent.impl.CodeAgent;
import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import io.sketch.mochaagents.llm.StreamingResponse;
import io.sketch.mochaagents.tool.ToolRegistry;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example09 — 对应 smolagents 的 multi_llm_agent.py.
 *
 * <p>演示多 LLM 路由/负载均衡 — 将请求分发到多个模型实例.
 *
 * <pre>
 *   smolagents 对应:
 *     model = LiteLLMRouterModel(
 *         model_id="model-group-1",
 *         model_list=[...],
 *         client_kwargs={"routing_strategy": "simple-shuffle"}
 *     )
 *     agent = CodeAgent(tools=[WebSearchTool()], model=model)
 * </pre>
 */
public final class Example09_MultiLLMAgent {

    /** 路由策略. */
    public enum RoutingStrategy {
        ROUND_ROBIN,       // 轮询
        RANDOM,            // 随机
        LEAST_USED,        // 最少使用
        SIMPLE_SHUFFLE     // 随机洗牌后轮询
    }

    /** 多 LLM 路由器 — 将请求分发给一组 LLM. */
    public static final class RouterLLM implements LLM {
        private final List<LLM> backends;
        private final RoutingStrategy strategy;
        private final AtomicInteger counter = new AtomicInteger(0);
        private final Map<LLM, AtomicInteger> usageCounts = new HashMap<>();
        private final List<LLM> shuffledBackends;

        public RouterLLM(List<LLM> backends, RoutingStrategy strategy) {
            this.backends = List.copyOf(backends);
            this.strategy = strategy;
            backends.forEach(b -> usageCounts.put(b, new AtomicInteger(0)));
            // 预洗牌
            List<LLM> shuffled = new ArrayList<>(this.backends);
            Collections.shuffle(shuffled);
            this.shuffledBackends = List.copyOf(shuffled);
        }

        private LLM select() {
            return switch (strategy) {
                case ROUND_ROBIN -> backends.get(counter.getAndIncrement() % backends.size());
                case RANDOM -> backends.get(new Random().nextInt(backends.size()));
                case LEAST_USED -> backends.stream()
                        .min(Comparator.comparingInt(b -> usageCounts.get(b).get()))
                        .orElse(backends.get(0));
                case SIMPLE_SHUFFLE -> shuffledBackends.get(counter.getAndIncrement() % shuffledBackends.size());
            };
        }

        @Override
        public LLMResponse complete(LLMRequest request) {
            LLM backend = select();
            usageCounts.get(backend).incrementAndGet();
            LLMResponse resp = backend.complete(request);
            return new LLMResponse(resp.content() + " [via " + backend.modelName() + "]",
                    backend.modelName(), resp.promptTokens(), resp.completionTokens(), 50, Map.of());
        }

        @Override public CompletableFuture<LLMResponse> completeAsync(LLMRequest request) {
            return CompletableFuture.completedFuture(complete(request));
        }

        @Override public StreamingResponse stream(LLMRequest request) {
            return select().stream(request);
        }

        @Override public String modelName() {
            return "router(" + backends.stream().map(LLM::modelName)
                    .reduce((a, b) -> a + "," + b).orElse("") + ")";
        }

        @Override public int maxContextTokens() {
            return backends.stream().mapToInt(LLM::maxContextTokens).min().orElse(4096);
        }
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("Example09: MultiLLMAgent — 多 LLM 路由/负载均衡");
        System.out.println("=".repeat(60));

        // 创建 3 个独立 LLM 实例（不走工厂缓存，测试多 LLM 路由）
        LLM gpt4 = LLMFactory.createNew();
        LLM claude = LLMFactory.createNew();
        LLM gemini = LLMFactory.createNew();

        List<LLM> backends = List.of(gpt4, claude, gemini);

        RoutingStrategy[] strategies = {
                RoutingStrategy.ROUND_ROBIN,
                RoutingStrategy.RANDOM,
                RoutingStrategy.LEAST_USED,
                RoutingStrategy.SIMPLE_SHUFFLE
        };

        for (RoutingStrategy strategy : strategies) {
            System.out.println("\n── Strategy: " + strategy + " ──");

            var routerLLM = new RouterLLM(backends, strategy);
            var registry = new ToolRegistry();

            var agent = CodeAgent.builder()
                    .name("router-agent")
                    .llm(routerLLM)
                    .toolRegistry(registry)
                    .maxSteps(3)
                    .build();

            // 运行 3 个任务观察路由分布
            String[] tasks = {"Tell me a joke", "Tell me a fact", "What is 2+2?"};
            for (String task : tasks) {
                String result = agent.run(task);
                System.out.println("  Task: \"" + task + "\" → " + result);
            }
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Example09 Complete.");
    }

    private Example09_MultiLLMAgent() {}
}
