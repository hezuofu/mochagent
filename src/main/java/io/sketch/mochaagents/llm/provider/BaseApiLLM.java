package io.sketch.mochaagents.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import io.sketch.mochaagents.llm.StreamingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base API LLM — JDK HttpClient, retry, rate limiting, SSE streaming.
 *
 * <p>Subclasses implement: {@link #buildRequestBody}, {@link #parseResponseContent}, {@link #apiUrl}.
 * @author lanxia39@163.com
 */
public abstract class BaseApiLLM implements LLM {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected static final ObjectMapper JSON = new ObjectMapper();

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_MS = 1000;
    private static final double RETRY_BACKOFF = 2.0;

    private final Semaphore rateLimiter;
    private final AtomicLong lastRequestTime = new AtomicLong(0);
    private final long minIntervalMs;

    protected final HttpClient httpClient;
    protected final String modelId;
    protected final int maxContextTokens;

    protected BaseApiLLM(Builder<?> builder) {
        this.modelId = builder.modelId;
        this.maxContextTokens = builder.maxContextTokens;

        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(builder.connectTimeoutSeconds));
        addHeaders(clientBuilder);
        this.httpClient = clientBuilder.build();

        if (builder.requestsPerMinute > 0) {
            this.rateLimiter = new Semaphore(builder.requestsPerMinute);
            this.minIntervalMs = 60_000 / builder.requestsPerMinute;
        } else {
            this.rateLimiter = null;
            this.minIntervalMs = 0;
        }
    }

    /** Subclasses override to add auth headers to every request. */
    protected void addHeaders(HttpClient.Builder builder) {}

    /** Subclasses override to return auth headers for each request. */
    protected Map<String, String> authHeaders() { return Map.of(); }

    protected abstract String apiUrl();
    protected abstract String buildRequestBody(LLMRequest request);
    protected abstract ResponseParseResult parseResponseContent(JsonNode root);

    protected record ResponseParseResult(String content, int promptTokens, int completionTokens) {}

    // ============ LLM接口 ============

    @Override
    public LLMResponse complete(LLMRequest request) {
        acquireRateLimit();
        String body = buildRequestBody(request);

        int attempt = 0;
        while (true) {
            attempt++;
            long start = System.currentTimeMillis();
            try {
                var reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl()))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .timeout(Duration.ofSeconds(120));
                authHeaders().forEach(reqBuilder::header);
                HttpRequest httpReq = reqBuilder.build();

                HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
                long latency = System.currentTimeMillis() - start;

                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    JsonNode root = JSON.readTree(resp.body());
                    ResponseParseResult parsed = parseResponseContent(root);
                    return new LLMResponse(parsed.content(), modelId,
                            parsed.promptTokens(), parsed.completionTokens(),
                            latency, Map.of("provider", getClass().getSimpleName()));
                }

                int code = resp.statusCode();
                String errorBody = resp.body();
                if (code != 429 && code < 500) {
                    throw new LLMException("API error " + code + ": " + errorBody, code);
                }
                if (attempt >= MAX_RETRIES) {
                    throw new LLMException("API error " + code + " after " + MAX_RETRIES + " retries: " + errorBody, code);
                }
                log.warn("{} attempt {}/{} failed with {}: {}. Retrying...",
                        getClass().getSimpleName(), attempt, MAX_RETRIES, code, errorBody);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LLMException("Interrupted during retry", e);
            } catch (IOException e) {
                if (attempt >= MAX_RETRIES) {
                    throw new LLMException("Network error after " + MAX_RETRIES + " retries: " + e.getMessage(), 0, e);
                }
                log.warn("{} attempt {}/{} network error: {}. Retrying...",
                        getClass().getSimpleName(), attempt, MAX_RETRIES, e.getMessage());
            }

            try {
                long delay = (long) (RETRY_BASE_MS * Math.pow(RETRY_BACKOFF, attempt - 1));
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LLMException("Interrupted during retry", e);
            }
        }
    }

    @Override
    public CompletableFuture<LLMResponse> completeAsync(LLMRequest request) {
        return CompletableFuture.supplyAsync(() -> complete(request));
    }

    @Override
    public StreamingResponse stream(LLMRequest request) {
        StreamingResponse response = new StreamingResponse();
        String body = buildStreamRequestBody(request);
        var reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        authHeaders().forEach(reqBuilder::header);
        HttpRequest httpReq = reqBuilder.build();

        Thread streamThread = new Thread(() -> {
            try {
                HttpResponse<java.io.InputStream> resp = httpClient.send(httpReq,
                        HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    String errorBody = new String(resp.body().readAllBytes());
                    response.error(new LLMException("Stream error " + resp.statusCode() + ": " + errorBody, resp.statusCode()));
                    return;
                }
                parseSseStream(resp.body(), response);
                response.complete();
            } catch (IOException e) {
                response.error(new LLMException("Stream network error: " + e.getMessage(), e));
            } catch (Exception e) {
                response.error(e);
            }
        });
        streamThread.setDaemon(true);
        streamThread.start();
        return response;
    }

    protected String buildStreamRequestBody(LLMRequest request) { return buildRequestBody(request); }

    protected void parseSseStream(java.io.InputStream stream, StreamingResponse response) throws IOException {
        String data = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) data = line.substring(6);
                else if (line.isEmpty() && !data.isEmpty()) {
                    if (!"[DONE]".equals(data)) parseSseData(data, response);
                    data = "";
                }
            }
        }
    }

    protected void parseSseData(String jsonData, StreamingResponse response) {
        try {
            JsonNode root = JSON.readTree(jsonData);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode delta = choices.get(0).get("delta");
                if (delta != null) {
                    JsonNode content = delta.get("content");
                    if (content != null && !content.isNull()) response.push(content.asText());
                }
            }
        } catch (Exception e) { log.debug("SSE parse error: {}", e.getMessage()); }
    }

    // ============ Helpers ============

    protected static ArrayNode messagesToJson(List<Map<String, String>> messages) {
        ArrayNode arr = JSON.createArrayNode();
        for (Map<String, String> msg : messages) {
            ObjectNode node = JSON.createObjectNode();
            node.put("role", msg.getOrDefault("role", "user"));
            node.put("content", msg.getOrDefault("content", ""));
            arr.add(node);
        }
        return arr;
    }

    protected static ArrayNode typedMessagesToJson(List<io.sketch.mochaagents.agent.message.Message> messages) {
        ArrayNode arr = JSON.createArrayNode();
        for (var msg : messages) {
            ObjectNode node = JSON.createObjectNode();
            node.put("role", msg.role());
            if (msg instanceof io.sketch.mochaagents.agent.message.Message.UserMessage u) {
                if (u.toolResults().isEmpty()) node.put("content", u.content());
                else {
                    ArrayNode content = arr.arrayNode();
                    if (u.content() != null && !u.content().isEmpty())
                        content.addObject().put("type", "text").put("text", u.content());
                    for (var tr : u.toolResults())
                        if (tr instanceof io.sketch.mochaagents.agent.message.ContentBlock.ToolResultBlock tb) {
                            ObjectNode trNode = content.addObject();
                            trNode.put("type", "tool_result"); trNode.put("tool_use_id", tb.toolUseId());
                            trNode.put("content", tb.content());
                            if (tb.isError()) trNode.put("is_error", true);
                        }
                    node.set("content", content);
                }
            } else if (msg instanceof io.sketch.mochaagents.agent.message.Message.AssistantMessage a) {
                ArrayNode content = arr.arrayNode();
                for (var b : a.content()) {
                    if (b instanceof io.sketch.mochaagents.agent.message.ContentBlock.TextBlock t)
                        content.addObject().put("type", "text").put("text", t.text());
                    else if (b instanceof io.sketch.mochaagents.agent.message.ContentBlock.ToolUseBlock tu) {
                        ObjectNode tuNode = content.addObject();
                        tuNode.put("type", "tool_use"); tuNode.put("id", tu.id());
                        tuNode.put("name", tu.name()); tuNode.set("input", JSON.valueToTree(tu.input()));
                    } else if (b instanceof io.sketch.mochaagents.agent.message.ContentBlock.ThinkingBlock th) {
                        ObjectNode thNode = content.addObject();
                        thNode.put("type", "thinking"); thNode.put("thinking", th.thought());
                        if (th.signature() != null) thNode.put("signature", th.signature());
                    }
                }
                node.set("content", content);
            } else if (msg instanceof io.sketch.mochaagents.agent.message.Message.SystemMessage s)
                node.put("content", s.content());
            arr.add(node);
        }
        return arr;
    }

    protected static int safeInt(JsonNode node, String field) {
        JsonNode val = node.get(field); return val != null && !val.isNull() ? val.asInt() : 0;
    }

    protected static String safeStr(JsonNode node, String field) {
        JsonNode val = node.get(field); return val != null && !val.isNull() ? val.asText() : "";
    }

    // ============ Rate limiting ============

    private void acquireRateLimit() {
        if (rateLimiter != null) { try { rateLimiter.acquire(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        if (minIntervalMs > 0) {
            long last = lastRequestTime.get(), now = System.currentTimeMillis();
            if (now - last < minIntervalMs) { try { Thread.sleep(minIntervalMs - (now - last)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
            lastRequestTime.set(System.currentTimeMillis());
        }
    }

    @Override public String modelName() { return modelId; }
    @Override public int maxContextTokens() { return maxContextTokens; }

    // ============ Builder ============

    @SuppressWarnings("unchecked")
    public abstract static class Builder<T extends Builder<T>> {
        protected String modelId;
        protected int maxContextTokens = 128000;
        protected int connectTimeoutSeconds = 30;
        protected int requestsPerMinute;

        public T modelId(String id) { this.modelId = id; return (T) this; }
        public T maxContextTokens(int tokens) { this.maxContextTokens = tokens; return (T) this; }
        public T connectTimeout(int seconds) { this.connectTimeoutSeconds = seconds; return (T) this; }
        public T requestsPerMinute(int rpm) { this.requestsPerMinute = rpm; return (T) this; }
    }

    // ============ Exception ============

    public static class LLMException extends RuntimeException {
        private final int statusCode;
        public LLMException(String msg) { super(msg); this.statusCode = 0; }
        public LLMException(String msg, int code) { super(msg); this.statusCode = code; }
        public LLMException(String msg, Throwable cause) { super(msg, cause); this.statusCode = 0; }
        public LLMException(String msg, int code, Throwable cause) { super(msg, cause); this.statusCode = code; }
        public int statusCode() { return statusCode; }
        public boolean isRateLimit() { return statusCode == 429; }
        public boolean isRetryable() { return statusCode == 429 || statusCode >= 500; }
    }
}
