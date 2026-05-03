package io.sketch.mochaagents.examples.tools;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;

import java.util.*;

/**
 * SQL 查询工具 — 对应 smolagents 的 sql_engine.
 *
 * <p>维护一个内存中的 receipts 表，支持 SELECT 查询.
 * @author lanxia39@163.com
 */
public final class SQLTool implements Tool {

    private final List<Map<String, Object>> rows = new ArrayList<>();

    public SQLTool() {
        rows.add(Map.of("receipt_id", 1, "customer_name", "Alan Payne", "price", 12.06, "tip", 1.20));
        rows.add(Map.of("receipt_id", 2, "customer_name", "Alex Mason", "price", 23.86, "tip", 0.24));
        rows.add(Map.of("receipt_id", 3, "customer_name", "Woodrow Wilson", "price", 53.43, "tip", 5.43));
        rows.add(Map.of("receipt_id", 4, "customer_name", "Margaret James", "price", 21.11, "tip", 1.00));
    }

    @Override public String getName() { return "sql_engine"; }
    @Override public String getDescription() {
        return "Allows you to perform SQL queries on the receipts table.\n"
                + "Columns: receipt_id INTEGER, customer_name VARCHAR, price FLOAT, tip FLOAT";
    }
    @Override public Map<String, ToolInput> getInputs() {
        return Map.of("query", ToolInput.string("The SQL query to execute"));
    }
    @Override public String getOutputType() { return "string"; }
    @Override public Object call(Map<String, Object> args) {
        String query = String.valueOf(args.getOrDefault("query", "")).toLowerCase().trim();

        if (query.contains("max(price)") || query.contains("most expensive")) {
            Map<String, Object> maxRow = rows.stream()
                    .max(Comparator.comparing(r -> (Double) r.get("price")))
                    .orElse(Map.of());
            return maxRow.toString();
        }
        if (query.contains("sum(")) {
            double sum = rows.stream().mapToDouble(r -> (Double) r.get("price")).sum();
            return "SUM(price) = " + sum;
        }
        if (query.contains("count(")) {
            return "COUNT = " + rows.size();
        }

        StringBuilder sb = new StringBuilder();
        for (var row : rows) {
            sb.append(row).append("\n");
        }
        return sb.toString();
    }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
}
