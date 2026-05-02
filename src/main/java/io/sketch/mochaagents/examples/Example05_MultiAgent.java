package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.core.impl.CodeAgent;
import io.sketch.mochaagents.core.impl.ToolCallingAgent;
import io.sketch.mochaagents.llm.provider.MockLLM;
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolRegistry;
import io.sketch.mochaagents.examples.tools.WikipediaTool;

import java.util.Map;

/**
 * Example05 — 对应 smolagents 的 inspect_multiagent_run.py.
 *
 * <p>演示 Managed Agents 模式：ToolCallingAgent 作为子 Agent 被 CodeAgent 管理调度.
 *
 * <pre>
 *   smolagents 对应:
 *     search_agent = ToolCallingAgent(tools=[WebSearchTool(), VisitWebpageTool()], model=model)
 *     manager_agent = CodeAgent(tools=[], model=model, managed_agents=[search_agent])
 *     manager_agent.run("If the US keeps its 2024 growth rate, how many years for GDP to double?")
 * </pre>
 */
public final class Example05_MultiAgent {

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("Example05: MultiAgent — Managed Agent 委托模式");
        System.out.println("=".repeat(60));

        var llm = LLMFactory.create();

        // ── 子 Agent: 搜索 Agent ──
        var searchRegistry = new ToolRegistry();
        searchRegistry.register(new WikipediaTool());

        ToolCallingAgent searchAgent = ToolCallingAgent.builder()
                .name("search_agent")
                .llm(llm)
                .toolRegistry(searchRegistry)
                .maxSteps(3)
                .build();

        // ── 管理 Agent: 将子 Agent 注册为工具 ──
        var managerRegistry = new ToolRegistry();
        managerRegistry.register(new AgentAsTool(searchAgent));

        CodeAgent managerAgent = CodeAgent.builder()
                .name("manager_agent")
                .llm(MockLLM.with(req -> {
                    String lastMsg = lastUserMsg(req);
                    return "<code>\n"
                            + "result = search_agent(task=\"" + lastMsg + "\")\n"
                            + "print(result)\n"
                            + "final_answer(result)\n"
                            + "</code>";
                }))
                .toolRegistry(managerRegistry)
                .maxSteps(3)
                .build();

        System.out.println("\nManager delegates task to search_agent:");
        String result = managerAgent.run(
                "If the US keeps its 2024 growth rate, how many years would it take for the GDP to double?"
        );
        System.out.println("ManagerAgent result: " + result);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Example05 Complete.");
    }

    private static String lastUserMsg(io.sketch.mochaagents.llm.LLMRequest req) {
        var msgs = req.messages();
        if (msgs != null) for (int i = msgs.size() - 1; i >= 0; i--) {
            if ("user".equals(msgs.get(i).get("role")))
                return msgs.get(i).get("content");
        }
        return req.prompt();
    }

    /** Agent 作为 Tool 暴露给父 Agent 调用. */
    static final class AgentAsTool implements Tool {
        private final ToolCallingAgent agent;
        AgentAsTool(ToolCallingAgent agent) { this.agent = agent; }
        @Override public String getName() { return agent.metadata().name(); }
        @Override public String getDescription() {
            String desc = agent.metadata().description();
            return desc != null && !desc.isEmpty() ? desc : "Delegated agent";
        }
        @Override public Map<String, ToolInput> getInputs() {
            return Map.of("task", ToolInput.string("The task to delegate"));
        }
        @Override public String getOutputType() { return "string"; }
        @Override public Object call(Map<String, Object> args) {
            String task = (String) args.getOrDefault("task", "");
            return agent.run(task);
        }
        @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
    }

    private Example05_MultiAgent() {}
}
