package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.agent.impl.CodeAgent;
import io.sketch.mochaagents.agent.impl.ToolCallingAgent;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolRegistry;

import java.util.Map;

/**
 * Example11 — 对应 smolagents 的 open_deep_research/run.py.
 *
 * <p>演示复杂多 Agent 研究流水线：
 * <ul>
 *   <li>Manager Agent (CodeAgent) 拆解问题、协调子 Agent</li>
 *   <li>Search Agent (ToolCallingAgent) 执行具体搜索任务</li>
 *   <li>Visualizer Tool 处理数据可视化</li>
 * </ul>
 *
 * <pre>
 *   smolagents 对应:
 *     text_webbrowser_agent = ToolCallingAgent(tools=WEB_TOOLS, ...)
 *     manager_agent = CodeAgent(tools=[visualizer, ...], managed_agents=[text_webbrowser_agent])
 *     answer = manager_agent.run(question)
 * </pre>
 * @author lanxia39@163.com
 */
public final class Example11_DeepResearch {

    /** 模拟文本检查工具. */
    static final class InspectorTool implements Tool {
        @Override public String getName() { return "inspect_file_as_text"; }
        @Override public String getDescription() {
            return "Inspects a file and returns its text content. Input: file_path";
        }
        @Override public Map<String, ToolInput> getInputs() {
            return Map.of("file_path", ToolInput.string("Path to the file"));
        }
        @Override public String getOutputType() { return "string"; }
        @Override public Object call(Map<String, Object> args) {
            String path = (String) args.getOrDefault("file_path", "");
            return "Content of " + path + ":\n[Sample research data for " + path + "]";
        }
        @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
    }

    /** 模拟可视化工具. */
    static final class VisualizerTool implements Tool {
        @Override public String getName() { return "visualizer"; }
        @Override public String getDescription() {
            return "Creates visualizations from data. Input: data (string description)";
        }
        @Override public Map<String, ToolInput> getInputs() {
            return Map.of("data", ToolInput.string("Data description to visualize"));
        }
        @Override public String getOutputType() { return "string"; }
        @Override public Object call(Map<String, Object> args) {
            return "[Visualizer] Chart generated for: " + args.getOrDefault("data", "");
        }
        @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
    }

    /** 模拟浏览器工具集. */
    static final class SearchTool implements Tool {
        @Override public String getName() { return "web_search"; }
        @Override public String getDescription() {
            return "Searches the web for information. Input: query";
        }
        @Override public Map<String, ToolInput> getInputs() {
            return Map.of("query", ToolInput.string("Search query"));
        }
        @Override public String getOutputType() { return "string"; }
        @Override public Object call(Map<String, Object> args) {
            String q = (String) args.getOrDefault("query", "");
            return "Search results for '" + q + "':\n"
                    + "1. Research paper: Key findings in " + q + " (2024)\n"
                    + "2. Article: Recent developments in " + q + "\n"
                    + "3. Database entry: " + q + " statistics";
        }
        @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("Example11: DeepResearch — 多 Agent 研究流水线");
        System.out.println("=".repeat(60));

        // ── 搜索子 Agent ──
        var searchRegistry = new ToolRegistry();
        searchRegistry.register(new SearchTool());
        searchRegistry.register(new InspectorTool());

        ToolCallingAgent searchAgent = ToolCallingAgent.builder()
                .name("search_agent")
                .description("Searches the web to answer research questions. "
                        + "Ask for all questions requiring web browsing.")
                .llm(LLMFactory.create())
                .toolRegistry(searchRegistry)
                .maxSteps(5)
                .planningInterval(3)
                .build();

        // ── 管理 Agent ──
        var managerRegistry = new ToolRegistry();
        managerRegistry.register(new VisualizerTool());
        // 将子 Agent 包装为工具
        managerRegistry.register(new AgentTool(searchAgent));

        CodeAgent managerAgent = CodeAgent.builder()
                .name("research_manager")
                .llm(io.sketch.mochaagents.examples.LLMFactory.createNew())
                .toolRegistry(managerRegistry)
                .maxSteps(5)
                .planningInterval(2)
                .build();

        String question = "How many studio albums did Mercedes Sosa release before 2007?";
        System.out.println("\nResearch Question: " + question);
        System.out.println("\nPipeline: ManagerAgent → delegates to → SearchAgent → returns → ManagerAgent\n");

        String result = managerAgent.run(question);
        System.out.println("Final Answer: " + result);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Example11 Complete.");
    }

    private static String lastUserMsg(io.sketch.mochaagents.llm.LLMRequest req) {
        var msgs = req.messages();
        if (msgs != null) for (int i = msgs.size() - 1; i >= 0; i--) {
            if ("user".equals(msgs.get(i).get("role"))) return msgs.get(i).get("content");
        }
        return req.prompt();
    }

    /** Agent 作为 Tool 适配器. */
    static final class AgentTool implements Tool {
        private final ToolCallingAgent agent;
        AgentTool(ToolCallingAgent agent) { this.agent = agent; }
        @Override public String getName() { return agent.metadata().name(); }
        @Override public String getDescription() { return "Delegated agent for research tasks"; }
        @Override public Map<String, ToolInput> getInputs() {
            return Map.of("query", ToolInput.string("The search query"));
        }
        @Override public String getOutputType() { return "string"; }
        @Override public Object call(Map<String, Object> args) {
            String q = (String) args.getOrDefault("query", "");
            return agent.run(q);
        }
        @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
    }

    private Example11_DeepResearch() {}
}
