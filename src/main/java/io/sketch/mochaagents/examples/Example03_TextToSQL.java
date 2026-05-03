package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.agent.impl.CodeAgent;
import io.sketch.mochaagents.tool.ToolRegistry;

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
 * @author lanxia39@163.com
 */
public final class Example03_TextToSQL {

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("Example03: TextToSQL — SQL 查询 Agent");
        System.out.println("=".repeat(60));

        var registry = new ToolRegistry();
        // Inline SQL tool — simple in-memory query engine
        registry.register(new io.sketch.mochaagents.tool.Tool() {
            private final java.util.List<java.util.Map<String, Object>> rows = java.util.List.of(
                    java.util.Map.of("receipt_id", 1, "customer_name", "Alan Payne", "price", 12.06, "tip", 1.20),
                    java.util.Map.of("receipt_id", 2, "customer_name", "Alex Mason", "price", 23.86, "tip", 0.24),
                    java.util.Map.of("receipt_id", 3, "customer_name", "Woodrow Wilson", "price", 53.43, "tip", 5.43));
            @Override public String getName() { return "sql_engine"; }
            @Override public String getDescription() { return "Query the receipts table (receipt_id, customer_name, price, tip)."; }
            @Override public java.util.Map<String, io.sketch.mochaagents.tool.ToolInput> getInputs() {
                return java.util.Map.of("query", io.sketch.mochaagents.tool.ToolInput.string("SQL query"));
            }
            @Override public String getOutputType() { return "string"; }
            @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
            @Override public Object call(java.util.Map<String, Object> args) {
                String q = String.valueOf(args.getOrDefault("query", "")).toLowerCase();
                if (q.contains("max(price)")) return rows.stream().max(java.util.Comparator.comparing(r -> (Double) r.get("price"))).toString();
                if (q.contains("sum(")) return "SUM(price) = " + rows.stream().mapToDouble(r -> (Double) r.get("price")).sum();
                return rows.toString();
            }
        });

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
