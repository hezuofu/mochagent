package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.tool.impl.WebSearchTool;

import io.sketch.mochaagents.agent.impl.CodeAgent;
import io.sketch.mochaagents.agent.impl.ToolCallingAgent;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example14 — 对应 smolagents 的 inspect_multiagent_run.py.
 *
 * <p>演示 OpenTelemetry/Phoenix 风格的多 Agent 执行遥测:
 * <ul>
 *   <li>Span 追踪：每个 Agent 步骤创建追踪跨度</li>
 *   <li>Token 用量：记录每次 LLM 调用的输入/输出 token</li>
 *   <li>耗时统计：每个 Agent 和工具调用的耗时</li>
 *   <li>Managed Agents：子 Agent 调用链路追踪</li>
 * </ul>
 *
 * <pre>
 *   smolagents 对应:
 *     from openinference.instrumentation.smolagents import SmolagentsInstrumentor
 *     from phoenix.otel import register
 *     register()
 *     SmolagentsInstrumentor().instrument(skip_dep_check=True)
 *     manager_agent.run(...)
 *     print(agent.monitor.get_total_token_counts())
 *     print(result.token_usage)
 * </pre>
 * @author lanxia39@163.com
 */
public final class Example14_InspectMultiAgentRun {

    // ─── 遥测系统 (模拟 OpenTelemetry + Phoenix) ───

    /** 执行追踪跨度 */
    static final class Span {
        final String name;
        final String type; // agent / tool / llm_call
        final Instant startTime;
        Instant endTime;
        final Map<String, Object> attributes = new LinkedHashMap<>();
        final List<Span> children = new ArrayList<>();
        final List<String> events = new ArrayList<>();

        Span(String name, String type) {
            this.name = name;
            this.type = type;
            this.startTime = Instant.now();
        }

        void finish() { this.endTime = Instant.now(); }

        Duration duration() {
            if (endTime == null) return Duration.between(startTime, Instant.now());
            return Duration.between(startTime, endTime);
        }

        void addChild(Span child) { children.add(child); }

        void addEvent(String event) { events.add(event); }

        void attribute(String key, Object value) { attributes.put(key, value); }
    }

    /** 遥测监视器 */
    static final class TelemetryMonitor {
        private final List<Span> rootSpans = new ArrayList<>();
        private final ThreadLocal<Deque<Span>> spanStack =
                ThreadLocal.withInitial(ArrayDeque::new);
        private final Map<String, AtomicInteger> tokenCounts = new ConcurrentHashMap<>();
        private final Map<String, Duration> durations = new ConcurrentHashMap<>();

        Span startSpan(String name, String type) {
            Span span = new Span(name, type);
            Deque<Span> stack = spanStack.get();
            if (!stack.isEmpty()) {
                stack.peek().addChild(span);
            } else {
                rootSpans.add(span);
            }
            stack.push(span);
            return span;
        }

        void endSpan(Span span) {
            span.finish();
            Deque<Span> stack = spanStack.get();
            if (!stack.isEmpty() && stack.peek() == span) {
                stack.pop();
            }
            durations.merge(span.name, span.duration(), Duration::plus);
        }

        void recordTokens(String model, int inputTokens, int outputTokens) {
            tokenCounts.computeIfAbsent(model + "_input", k -> new AtomicInteger())
                    .addAndGet(inputTokens);
            tokenCounts.computeIfAbsent(model + "_output", k -> new AtomicInteger())
                    .addAndGet(outputTokens);
        }

        /** 打印完整遥测报告 */
        void printReport() {
            System.out.println("\n╔══════════════════════════════════════════════════════╗");
            System.out.println("║          📊 Telemetry Report (Phoenix-style)         ║");
            System.out.println("╚══════════════════════════════════════════════════════╝");

            // Token 用量
            System.out.println("\n┌─ Token Usage ───────────────────────────────────────┐");
            for (var entry : tokenCounts.entrySet()) {
                System.out.printf("│  %-30s : %d%n", entry.getKey(), entry.getValue().get());
            }
            System.out.println("└──────────────────────────────────────────────────────┘");

            // 耗时统计
            System.out.println("\n┌─ Timing Information ────────────────────────────────┐");
            for (var entry : durations.entrySet()) {
                System.out.printf("│  %-30s : %d ms%n",
                        entry.getKey(), entry.getValue().toMillis());
            }
            System.out.println("└──────────────────────────────────────────────────────┘");

            // Span 层级树
            System.out.println("\n┌─ Span Tree ─────────────────────────────────────────┐");
            for (Span root : rootSpans) {
                printSpanTree(root, 0);
            }
            System.out.println("└──────────────────────────────────────────────────────┘");
        }

        private void printSpanTree(Span span, int depth) {
            String indent = "│  ".repeat(depth);
            String branch = depth > 0 ? "├─ " : "";
            String durationStr = span.duration() != null
                    ? String.format(" (%dms)", span.duration().toMillis()) : "";
            System.out.printf("%s%s[%s] %s%s%n",
                    indent, branch, span.type.toUpperCase(), span.name, durationStr);
            for (String event : span.events) {
                System.out.printf("%s│  ⚡ %s%n", indent, event);
            }
            for (Span child : span.children) {
                printSpanTree(child, depth + 1);
            }
        }
    }

    // ─── Agent 实现 ───

    /** 带遥测的 Search Agent 封装 */
    static ToolCallingAgent createSearchAgent(TelemetryMonitor telemetry) {
        var registry = new ToolRegistry();
        registry.register(new io.sketch.mochaagents.tool.impl.WebSearchTool());
        registry.register(new io.sketch.mochaagents.tool.impl.WebFetchTool());

        return ToolCallingAgent.builder()
                .name("search_agent")
                .description("Agent that can do web search and Wikipedia lookups")
                .llm(LLMFactory.create())
                .toolRegistry(registry)
                .maxSteps(3)
                .build();
    }

    /** Agent 作为 Tool 暴露（带遥测追踪） */
    static final class TelemetryAgentTool implements Tool {
        private final ToolCallingAgent agent;
        private final TelemetryMonitor telemetry;

        TelemetryAgentTool(ToolCallingAgent agent, TelemetryMonitor telemetry) {
            this.agent = agent;
            this.telemetry = telemetry;
        }

        @Override public String getName() { return agent.metadata().name(); }
        @Override public String getDescription() {
            return "Delegated agent that can search the web";
        }
        @Override public Map<String, ToolInput> getInputs() {
            return Map.of("task", ToolInput.string("The task to delegate"));
        }
        @Override public String getOutputType() { return "string"; }
        @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }

        @Override
        public Object call(Map<String, Object> args) {
            String task = (String) args.getOrDefault("task", "");
            Span span = telemetry.startSpan("search_agent.run", "agent");
            span.attribute("task", task);

            try {
                // 模拟 LLM token 消耗
                telemetry.recordTokens("mock-model", 150, 80);
                String result = agent.run(task);
                span.addEvent("search completed: " +
                        result.substring(0, Math.min(50, result.length())) + "...");
                return result;
            } finally {
                telemetry.endSpan(span);
            }
        }
    }

    // ─── main ───

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("Example14: InspectMultiAgentRun — 多 Agent 遥测");
        System.out.println("=".repeat(60));

        var telemetry = new TelemetryMonitor();

        // 主 Span
        Span mainSpan = telemetry.startSpan("manager_agent.run", "agent");
        telemetry.recordTokens("mock-model", 200, 100);

        System.out.println("\n[1] Creating agents with OpenTelemetry instrumentation...");
        System.out.println("    ✓ SmolagentsInstrumentor.instrument() registered");

        // 子 Agent
        ToolCallingAgent searchAgent = createSearchAgent(telemetry);
        System.out.println("    ✓ SearchAgent created (tools: web_search, wikipedia)");

        // 管理 Agent
        var managerRegistry = new ToolRegistry();
        managerRegistry.register(new TelemetryAgentTool(searchAgent, telemetry));

        CodeAgent managerAgent = CodeAgent.builder()
                .name("manager_agent")
                .llm(io.sketch.mochaagents.examples.LLMFactory.createNew())
                .toolRegistry(managerRegistry)
                .maxSteps(3)
                .build();
        System.out.println("    ✓ ManagerAgent created (managed_agents=[search_agent])");

        // 执行多 Agent 任务
        System.out.println("\n[2] Running multi-agent task with telemetry...");
        String question = "If the US keeps its 2024 growth rate, "
                + "how many years would it take for the GDP to double?";

        telemetry.recordTokens("mock-model", 180, 95);
        Span searchSpan = telemetry.startSpan("web_search", "tool");
        searchSpan.attribute("query", "US GDP growth rate 2024");
        searchSpan.addEvent("found result: 2.8% growth rate");
        telemetry.endSpan(searchSpan);

        Span calcSpan = telemetry.startSpan("rule_of_72", "tool");
        calcSpan.attribute("growthRate", "2.8%");
        calcSpan.addEvent("72 / 2.8 = 25.7 years");
        telemetry.endSpan(calcSpan);

        String result = managerAgent.run(question);
        telemetry.endSpan(mainSpan);

        System.out.println("\n[3] Manager agent result:");
        System.out.println("    " + result);

        // 打印 Phoenix 风格遥测报告
        telemetry.printReport();

        // 展示与 smolagents 监控对应的信息
        System.out.println("\n┌─ smolagents Monitor Equivalents ────────────────────┐");
        System.out.println("│  agent.monitor.get_total_token_counts()              │");
        System.out.println("│  → See 'Token Usage' section above                  │");
        System.out.println("│                                                      │");
        System.out.println("│  result.timing                                       │");
        System.out.println("│  → See 'Timing Information' section above            │");
        System.out.println("│                                                      │");
        System.out.println("│  Phoenix UI: http://localhost:6006                   │");
        System.out.println("│  → See 'Span Tree' above (equivalent trace view)    │");
        System.out.println("└──────────────────────────────────────────────────────┘");

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Example14 Complete.");
    }

    private static String lastUserMsg(io.sketch.mochaagents.llm.LLMRequest req) {
        var msgs = req.messages();
        if (msgs != null) for (int i = msgs.size() - 1; i >= 0; i--) {
            if ("user".equals(msgs.get(i).get("role")))
                return msgs.get(i).get("content");
        }
        return req.prompt();
    }

    private Example14_InspectMultiAgentRun() {}
}
