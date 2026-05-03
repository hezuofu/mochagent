package io.sketch.mochaagents.examples.tools;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Wikipedia tool — calls real Wikipedia API (free, no key needed).
 * @author lanxia39@163.com
 */
public final class WikipediaTool implements Tool {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Override public String getName() { return "search_wikipedia"; }
    @Override public String getDescription() {
        return "Search Wikipedia and get a summary. Input: query (search term).";
    }
    @Override public Map<String, ToolInput> getInputs() {
        return Map.of("query", ToolInput.string("Search term to look up on Wikipedia"));
    }
    @Override public String getOutputType() { return "string"; }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }

    @Override
    public Object call(Map<String, Object> args) {
        String query = (String) args.getOrDefault("query", "");
        if (query.isEmpty()) return "No query provided.";

        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://en.wikipedia.org/api/rest_v1/page/summary/" + encoded;
            String resp = HTTP.send(HttpRequest.newBuilder().uri(URI.create(url))
                    .header("User-Agent", "MochaAgent/1.0").GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();

            // Extract title and extract from JSON
            String title = extractJsonString(resp, "title");
            String extract = extractJsonString(resp, "extract");
            if (title != null && extract != null) {
                return title + ": " + (extract.length() > 500 ? extract.substring(0, 500) + "..." : extract);
            }
            return "No Wikipedia result for: " + query;
        } catch (Exception e) {
            return "Wikipedia lookup failed: " + e.getMessage();
        }
    }

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end).replace("\\\"", "\"").replace("\\n", "\n");
    }
}
