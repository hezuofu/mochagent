package io.sketch.mochaagents.examples.tools;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;

import java.util.Map;

/**
 * 模拟网页搜索工具 — 返回模拟搜索结果.
 * @author lanxia39@163.com
 */
public final class WebSearchTool implements Tool {

    private static final Map<String, String> KNOWLEDGE;

    static {
        Map<String, String> map = new java.util.HashMap<>();
        map.put("capital of france", "Paris is the capital and most populous city of France.");
        map.put("GDP growth", "US 2024 GDP growth rate was approximately 2.8%.");
        map.put("rule of 72", "The Rule of 72: divide 72 by the growth rate to estimate doubling years. e.g. 72/2.8 ≈ 25.7 years.");
        map.put("largest ocean", "The Pacific Ocean is the largest and deepest ocean on Earth.");
        map.put("java version", "Java 21 (LTS) was released in September 2023 with virtual threads and pattern matching.");
        map.put("python", "Python is a high-level, interpreted programming language created by Guido van Rossum.");
        map.put("moon landing", "Apollo 11 landed on the Moon on July 20, 1969. Neil Armstrong was the first person to walk on the Moon.");
        map.put("machine learning", "Machine learning is a subset of AI that enables systems to learn from data without explicit programming.");
        map.put("paris", "Paris is the capital of France, known for the Eiffel Tower, the Louvre, and Notre-Dame.");
        map.put("london", "London is the capital of the United Kingdom, home to Buckingham Palace and the British Museum.");
        map.put("tokyo", "Tokyo is the capital of Japan, the world's most populous metropolitan area.");
        map.put("new york", "New York City is the most populous city in the United States, known for Wall Street and Broadway.");
        map.put("climate change", "Climate change refers to long-term shifts in temperatures and weather patterns, primarily driven by human activities.");
        KNOWLEDGE = java.util.Collections.unmodifiableMap(map);
    }

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "Search the web for information. Returns relevant snippets about the query.";
    }

    @Override
    public Map<String, ToolInput> getInputs() {
        return Map.of("query", ToolInput.string("The search query string"));
    }

    @Override
    public String getOutputType() {
        return "string";
    }

    @Override
    public Object call(Map<String, Object> args) {
        String query = (String) args.getOrDefault("query", "");
        String lower = query.toLowerCase();

        // 关键词匹配
        for (var entry : KNOWLEDGE.entrySet()) {
            if (lower.contains(entry.getKey()) || entry.getKey().contains(lower)) {
                return "[Web Search Result] " + entry.getValue();
            }
        }

        // 默认结果
        return "[Web Search Result] No specific information found for: \"" + query
                + "\". Try refining your search terms.";
    }

    @Override
    public SecurityLevel getSecurityLevel() {
        return SecurityLevel.LOW;
    }
}
