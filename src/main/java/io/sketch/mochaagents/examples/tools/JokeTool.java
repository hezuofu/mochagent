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
 * Joke tool — calls JokeAPI (free, no key needed).
 * @author lanxia39@163.com
 */
public final class JokeTool implements Tool {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Override public String getName() { return "get_joke"; }
    @Override public String getDescription() { return "Get a random programming joke from JokeAPI."; }
    @Override public Map<String, ToolInput> getInputs() { return Map.of(); }
    @Override public String getOutputType() { return "string"; }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }

    @Override
    public Object call(Map<String, Object> args) {
        try {
            String url = "https://v2.jokeapi.dev/joke/Programming?type=single&format=txt";
            String resp = HTTP.send(HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            return resp.trim();
        } catch (Exception e) {
            return "Joke unavailable: " + e.getMessage();
        }
    }
}
