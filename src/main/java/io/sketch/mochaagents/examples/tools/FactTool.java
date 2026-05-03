package io.sketch.mochaagents.examples.tools;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Random fact tool — calls uselessfacts API (free, no key).
 * @author lanxia39@163.com
 */
public final class FactTool implements Tool {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Override public String getName() { return "get_random_fact"; }
    @Override public String getDescription() { return "Get a random interesting fact."; }
    @Override public Map<String, ToolInput> getInputs() { return Map.of(); }
    @Override public String getOutputType() { return "string"; }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }

    @Override
    public Object call(Map<String, Object> args) {
        try {
            String url = "https://uselessfacts.jsph.pl/api/v2/facts/random?language=en";
            String resp = HTTP.send(HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            // Extract text from {"text":"..."}
            int start = resp.indexOf("\"text\":\"") + 8;
            if (start < 8) return resp;
            int end = resp.indexOf('"', start);
            return end > start ? resp.substring(start, end).replace("\\\"", "\"") : resp;
        } catch (Exception e) {
            return "Random Fact: The first oranges weren't orange — they were green.";
        }
    }
}
