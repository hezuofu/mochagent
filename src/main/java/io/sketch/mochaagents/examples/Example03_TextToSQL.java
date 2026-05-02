package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.core.impl.CodeAgent;
import io.sketch.mochaagents.llm.provider.MockLLM;
import io.sketch.mochaagents.tool.ToolRegistry;
import io.sketch.mochaagents.examples.tools.SQLTool;

/**
 * Example03 — 对应 smolagents 的 text_to_sql.py.
 *
 * <p>构建 CodeAgent，连接 SQL 工具查询 receipts 表.
 *
 * <pre>
 *   smolagents 对应:
 *     agent = CodeAgent(tools=[sql_engine], model=model)
 *     agent.run("Can you give me the name of the client who got the most expensive receipt?")
 * </pre>
 */
public final class Example03_TextToSQL {

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("Example03: TextToSQL — SQL 查询 Agent");
        System.out.println("=".repeat(60));

        var registry = new ToolRegistry();
        registry.register(new SQLTool());

        var agent = CodeAgent.builder()
                .name("sql-agent")
                .llm(LLMFactory.create())
                .toolRegistry(registry)
                .maxSteps(3)
                .build();

        System.out.println("\nQuery: Who got the most expensive receipt?");
        String result = agent.run("Can you give me the name of the client who got the most expensive receipt?");
        System.out.println("Result: " + result);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Example03 Complete.");
    }

    private Example03_TextToSQL() {}
}
