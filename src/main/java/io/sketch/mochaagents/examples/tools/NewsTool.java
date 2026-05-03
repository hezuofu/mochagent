package io.sketch.mochaagents.examples.tools;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * News tool — fetches real RSS headlines from BBC News (free, no key).
 * @author lanxia39@163.com
 */
public final class NewsTool implements Tool {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final Pattern TITLE_PAT = Pattern.compile("<title>(?!BBC)([^<]+)</title>");

    @Override public String getName() { return "get_news_headlines"; }
    @Override public String getDescription() { return "Get current news headlines from BBC RSS feed."; }
    @Override public Map<String, ToolInput> getInputs() { return Map.of(); }
    @Override public String getOutputType() { return "string"; }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }

    @Override
    public Object call(Map<String, Object> args) {
        try {
            String url = "https://feeds.bbci.co.uk/news/rss.xml";
            String resp = HTTP.send(HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();

            List<String> titles = new ArrayList<>();
            Matcher m = TITLE_PAT.matcher(resp);
            int count = 0;
            while (m.find() && count < 5) {
                titles.add((count + 1) + ". " + m.group(1).trim());
                count++;
            }

            if (titles.isEmpty()) return "No news headlines available.";
            return "BBC News Headlines:\n" + String.join("\n", titles);
        } catch (Exception e) {
            return "News unavailable: " + e.getMessage();
        }
    }
}
