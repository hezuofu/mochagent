package io.sketch.mochaagents.examples.tools;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;

import java.util.Map;

/**
 * 搜索检索工具 — 模拟文档检索，用于 RAG 示例.
 */
public final class RetrieverTool implements Tool {

    private final Map<String, String> documents;

    public RetrieverTool(Map<String, String> documents) {
        this.documents = documents;
    }

    @Override public String getName() { return "retriever"; }
    @Override public String getDescription() {
        return "Uses semantic search to retrieve relevant documents for a query.";
    }
    @Override public Map<String, ToolInput> getInputs() {
        return Map.of("query", ToolInput.string("The search query"));
    }
    @Override public String getOutputType() { return "string"; }
    @Override public Object call(Map<String, Object> args) {
        String query = String.valueOf(args.getOrDefault("query", "")).toLowerCase();
        String[] queryWords = query.split("\\s+");
        StringBuilder sb = new StringBuilder("Retrieved documents:\n");
        int count = 0;
        for (var entry : documents.entrySet()) {
            String keyLower = entry.getKey().toLowerCase();
            String valueLower = entry.getValue().toLowerCase();
            // 匹配：任意关键词命中 key 或 value
            boolean matched = false;
            for (String word : queryWords) {
                if (word.length() > 2 && (keyLower.contains(word) || valueLower.contains(word))) {
                    matched = true;
                    break;
                }
            }
            if (matched) {
                sb.append("\n===== Document ").append(count).append(" =====\n")
                        .append(entry.getValue());
                count++;
                if (count >= 3) break;
            }
        }
        if (count == 0) {
            sb.append("\nNo matching documents found.");
        }
        return sb.toString();
    }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
}
