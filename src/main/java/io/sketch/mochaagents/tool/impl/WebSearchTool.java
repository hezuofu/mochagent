package io.sketch.mochaagents.tool.impl;

import io.sketch.mochaagents.tool.AbstractTool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolSchema;
import io.sketch.mochaagents.tool.ValidationResult;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web 搜索工具 — 对齐 claude-code 的 WebSearchTool.
 *
 * <p>使用 DuckDuckGo Instant Answer API（免 API Key）或可配置搜索引擎。
 * 只读、并发安全。
 * @author lanxia39@163.com
 */
public class WebSearchTool extends AbstractTool {

    private static final String NAME = "web_search";
    private static final String DEFAULT_SEARCH_URL =
            "https://api.duckduckgo.com/?q=%s&format=json&no_html=1&skip_disambig=1";

    private final HttpClient httpClient;

    public WebSearchTool() {
        super(builder(NAME, "Search the web for current information. "
                        + "Returns relevant URLs and snippets.",
                SecurityLevel.LOW)
                .readOnly(true)
                .concurrencySafe(true)
                .searchHint("search the web for current information")
        );
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .inputType("object")
                .inputRequired("query")
                .inputProperty("query", "string", "The search query to use (min 2 chars)", true)
                .inputProperty("allowed_domains", "array", "Only include results from these domains", false)
                .inputProperty("blocked_domains", "array", "Never include results from these domains", false)
                .outputType("object")
                .outputProperty("query", "string", "The search query that was executed")
                .outputProperty("results", "array", "Search result objects with title and url")
                .outputProperty("durationSeconds", "number", "Time taken for the search")
                .build();
    }

    @Override
    public Map<String, ToolInput> getInputs() {
        Map<String, ToolInput> inputs = new LinkedHashMap<>();
        inputs.put("query", ToolInput.string("The search query to use"));
        inputs.put("allowed_domains", new ToolInput("array", "Only include results from these domains", true));
        inputs.put("blocked_domains", new ToolInput("array", "Never include results from these domains", true));
        return inputs;
    }

    @Override
    public String getOutputType() { return "object"; }

    @Override
    public ValidationResult validateInput(Map<String, Object> arguments) {
        String query = (String) arguments.get("query");
        if (query == null || query.length() < 2) {
            return ValidationResult.invalid("query must be at least 2 characters", 1);
        }
        return ValidationResult.valid();
    }

    @Override
    public Object call(Map<String, Object> arguments) {
        long start = System.currentTimeMillis();
        String query = (String) arguments.get("query");
        @SuppressWarnings("unchecked")
        List<String> allowedDomains = (List<String>) arguments.get("allowed_domains");
        @SuppressWarnings("unchecked")
        List<String> blockedDomains = (List<String>) arguments.get("blocked_domains");

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = String.format(DEFAULT_SEARCH_URL, encodedQuery);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "MochaAgents/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            List<Map<String, String>> results = parseResults(response.body(), allowedDomains, blockedDomains);

            double durationSeconds = (System.currentTimeMillis() - start) / 1000.0;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("query", query);
            result.put("results", results);
            result.put("durationSeconds", durationSeconds);
            return result;

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Web search failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String formatResult(Object output, String toolUseId) {
        if (!(output instanceof Map)) return output != null ? output.toString() : "";
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) output;
        String query = (String) map.get("query");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> results = (List<Map<String, String>>) map.getOrDefault("results", List.of());

        StringBuilder sb = new StringBuilder();
        sb.append("Web search results for query: \"").append(query).append("\"\n\n");

        for (int i = 0; i < results.size(); i++) {
            Map<String, String> r = results.get(i);
            sb.append(i + 1).append(". ").append(r.getOrDefault("title", "Untitled")).append("\n");
            sb.append("   ").append(r.getOrDefault("url", "")).append("\n");
            String snippet = r.get("snippet");
            if (snippet != null && !snippet.isEmpty()) {
                sb.append("   ").append(snippet).append("\n");
            }
            sb.append("\n");
        }

        sb.append("\nREMINDER: Include the sources above in your response using markdown hyperlinks.");
        return sb.toString();
    }

    // ==================== Parsing ====================

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseResults(String body,
                                                    List<String> allowedDomains,
                                                    List<String> blockedDomains) {
        List<Map<String, String>> results = new ArrayList<>();

        // Simple JSON parsing for DuckDuckGo API response
        // More robust parsing should use Jackson, but keeping dependency-free
        try {
            String relatedTopics = extractJsonArray(body, "\"RelatedTopics\"");
            String resultsArray = extractJsonArray(body, "\"Results\"");

            // Parse RelatedTopics
            if (relatedTopics != null) {
                parseResultItems(relatedTopics, results, allowedDomains, blockedDomains);
            }

            // Parse Results
            if (resultsArray != null) {
                parseResultItems(resultsArray, results, allowedDomains, blockedDomains);
            }

            // Parse Abstract as fallback
            if (results.isEmpty()) {
                String abstractText = extractJsonString(body, "\"AbstractText\"");
                String abstractUrl = extractJsonString(body, "\"AbstractURL\"");
                String heading = extractJsonString(body, "\"Heading\"");
                if (abstractText != null && !abstractText.isEmpty()) {
                    Map<String, String> r = new LinkedHashMap<>();
                    r.put("title", heading != null ? heading : "Abstract");
                    r.put("url", abstractUrl != null ? abstractUrl : "");
                    r.put("snippet", abstractText);
                    results.add(r);
                }
            }
        } catch (Exception e) {
            Map<String, String> errorResult = new LinkedHashMap<>();
            errorResult.put("title", "Search Error");
            errorResult.put("url", "");
            errorResult.put("snippet", "Failed to parse search results: " + e.getMessage());
            results.add(errorResult);
        }

        return results;
    }

    private void parseResultItems(String json, List<Map<String, String>> results,
                                   List<String> allowedDomains, List<String> blockedDomains) {
        int pos = 0;
        while ((pos = json.indexOf("\"Text\"", pos)) >= 0) {
            String text = extractJsonStringAt(json, pos + 7);
            int firstUrlPos = json.indexOf("\"FirstURL\"", pos);
            final String url = firstUrlPos >= 0 ? extractJsonStringAt(json, firstUrlPos + 11) : "";

            if (text != null && !text.isEmpty()) {
                // Apply domain filters
                if (allowedDomains != null && !allowedDomains.isEmpty()) {
                    boolean allowed = allowedDomains.stream().anyMatch(d -> url.contains(d));
                    if (!allowed) { pos = firstUrlPos > 0 ? firstUrlPos + 1 : pos + 1; continue; }
                }
                if (blockedDomains != null && !blockedDomains.isEmpty()) {
                    boolean blocked = blockedDomains.stream().anyMatch(d -> url.contains(d));
                    if (blocked) { pos = firstUrlPos > 0 ? firstUrlPos + 1 : pos + 1; continue; }
                }

                Map<String, String> r = new LinkedHashMap<>();
                r.put("title", text);
                r.put("url", url);
                r.put("snippet", "");
                results.add(r);
            }
            pos = firstUrlPos > 0 ? firstUrlPos + 1 : pos + 1;
        }
    }

    private static String extractJsonArray(String json, String key) {
        int keyPos = json.indexOf(key);
        if (keyPos < 0) return null;
        int start = json.indexOf('[', keyPos);
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return json.substring(start, i + 1); }
        }
        return null;
    }

    private static String extractJsonString(String json, String key) {
        int keyPos = json.indexOf(key);
        if (keyPos < 0) return null;
        return extractJsonStringAt(json, keyPos + key.length());
    }

    private static String extractJsonStringAt(String json, int pos) {
        int colonPos = json.indexOf(':', pos);
        if (colonPos < 0) return null;
        int start = json.indexOf('"', colonPos);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }
}
