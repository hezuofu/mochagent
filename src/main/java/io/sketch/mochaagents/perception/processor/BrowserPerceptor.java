package io.sketch.mochaagents.perception.processor;

import io.sketch.mochaagents.perception.Observation;
import io.sketch.mochaagents.perception.PerceptionResult;
import io.sketch.mochaagents.perception.Perceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 浏览器感知器 — 获取网页内容，提取文本摘要.
 * @author lanxia39@163.com
 */
public class BrowserPerceptor implements Perceptor<String, Observation> {

    private static final Logger log = LoggerFactory.getLogger(BrowserPerceptor.class);
    private static final int MAX_CONTENT = 50000;
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public PerceptionResult<Observation> perceive(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "MochaAgent/1.0").timeout(Duration.ofSeconds(30))
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            String body = resp.body();
            String text = stripHtml(body);
            if (text.length() > MAX_CONTENT) text = text.substring(0, MAX_CONTENT) + "...";

            String summary = String.format("URL: %s (status=%d, %d chars)\n%s",
                    url, resp.statusCode(), body.length(), text);
            log.debug("BrowserPerceptor fetched {} ({} chars)", url, body.length());
            return PerceptionResult.of(
                    new Observation("browser", summary, "web"), "web");
        } catch (Exception e) {
            log.warn("BrowserPerceptor failed for {}: {}", url, e.getMessage());
            return PerceptionResult.of(
                    new Observation("browser", "Error fetching " + url + ": " + e.getMessage(), "web"), "web");
        }
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<script[^>]*>.*?</script>", " ")
                .replaceAll("<style[^>]*>.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&[a-zA-Z]+;", " ")
                .replaceAll("\\s+", " ").trim();
    }

    @Override
    public CompletableFuture<PerceptionResult<Observation>> perceiveAsync(String input) {
        return CompletableFuture.supplyAsync(() -> perceive(input));
    }
}
