package io.sketch.mochaagents.llm.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import io.sketch.mochaagents.llm.StreamingResponse;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 真实 API 模型抽象基类 — 提供 OkHttp 客户端、重试、限流、错误处理等通用能力.
 *
 * <p>子类只需实现:
 * <ul>
 *   <li>{@link #buildRequestBody(LLMRequest)} — 构造 API 请求体 JSON</li>
 *   <li>{@link #parseResponseContent(JsonNode)} — 从响应 JSON 中提取内容和 token 用量</li>
 *   <li>{@link #apiUrl()} — API 端点 URL</li>
 * </ul>
 *
 * <h3>重试策略</h3>
 * 指数退避: 1s → 2s → 4s, 最多 3 次. 仅对 429/5xx 和网络异常重试.
 *
 * <h3>限流</h3>
 * 通过 {@code requestsPerMinute} 控制请求频率, 使用 Semaphore + 最小间隔.
 */
public abstract class BaseApiLLM implements LLM {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final MediaType MEDIA_JSON = MediaType.parse("application/json; charset=utf-8");

    // 重试配置
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_MS = 1000;
    private static final double RETRY_BACKOFF = 2.0;

    // 限流
    private final Semaphore rateLimiter;
    private final AtomicLong lastRequestTime = new AtomicLong(0);
    private final long minIntervalMs;

    protected final OkHttpClient httpClient;
    protected final String modelId;
    protected final int maxContextTokens;

    protected BaseApiLLM(Builder<?> builder) {
        this.modelId = builder.modelId;
        this.maxContextTokens = builder.maxContextTokens;

        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                .connectTimeout(builder.connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(builder.readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(builder.writeTimeoutSeconds, TimeUnit.SECONDS);

        configureClient(clientBuilder);
        this.httpClient = clientBuilder.build();

        // 限流
        if (builder.requestsPerMinute > 0) {
            this.rateLimiter = new Semaphore(builder.requestsPerMinute);
            this.minIntervalMs = 60_000 / builder.requestsPerMinute;
        } else {
            this.rateLimiter = null;
            this.minIntervalMs = 0;
        }
    }

    /** 子类可覆写以添加认证头等. */
    protected void configureClient(OkHttpClient.Builder builder) {}

    /** API 端点 URL. */
    protected abstract String apiUrl();

    /** 构造请求体 JSON. */
    protected abstract String buildRequestBody(LLMRequest request);

    /** 解析响应, 返回 [content, promptTokens, completionTokens]. */
    protected abstract ResponseParseResult parseResponseContent(JsonNode root);

    /**
     * 解析结果记录.
     * @param content          响应文本
     * @param promptTokens     输入 token 数
     * @param completionTokens 输出 token 数
     */
    protected record ResponseParseResult(String content, int promptTokens, int completionTokens) {}

    // ============ LLM 接口实现 ============

    @Override
    public LLMResponse complete(LLMRequest request) {
        acquireRateLimit();

        String body = buildRequestBody(request);
        Request httpReq = new Request.Builder()
                .url(apiUrl())
                .post(RequestBody.create(body, MEDIA_JSON))
                .build();

        int attempt = 0;
        while (true) {
            attempt++;
            long start = System.currentTimeMillis();
            try (okhttp3.Response resp = httpClient.newCall(httpReq).execute()) {
                long latency = System.currentTimeMillis() - start;

                if (resp.isSuccessful()) {
                    String respBody = resp.body() != null ? resp.body().string() : "{}";
                    JsonNode root = JSON.readTree(respBody);
                    ResponseParseResult parsed = parseResponseContent(root);

                    return new LLMResponse(
                            parsed.content(), modelId,
                            parsed.promptTokens(), parsed.completionTokens(),
                            latency, Map.of("provider", getClass().getSimpleName())
                    );
                }

                // 客户端错误 (非 429) 不重试
                int code = resp.code();
                String errorBody = resp.body() != null ? resp.body().string() : "";
                if (code != 429 && code < 500) {
                    throw new LLMException("API error " + code + ": " + errorBody, code);
                }
                if (attempt >= MAX_RETRIES) {
                    throw new LLMException("API error " + code + " after " + MAX_RETRIES + " retries: " + errorBody, code);
                }
                log.warn("{} attempt {}/{} failed with {}: {}. Retrying...",
                        getClass().getSimpleName(), attempt, MAX_RETRIES, code, errorBody);

            } catch (IOException e) {
                if (attempt >= MAX_RETRIES) {
                    throw new LLMException("Network error after " + MAX_RETRIES + " retries: " + e.getMessage(), 0, e);
                }
                log.warn("{} attempt {}/{} network error: {}. Retrying...",
                        getClass().getSimpleName(), attempt, MAX_RETRIES, e.getMessage());
            }

            // 指数退避
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
        // 默认实现: 非流式调用后模拟流式
        StreamingResponse response = new StreamingResponse();
        new Thread(() -> {
            try {
                LLMResponse result = complete(request);
                for (String word : result.content().split("\\s+")) {
                    response.push(word + " ");
                }
                response.complete();
            } catch (Exception e) {
                response.push("[Error: " + e.getMessage() + "]");
                response.complete();
            }
        }).start();
        return response;
    }

    @Override
    public String modelName() {
        return modelId;
    }

    @Override
    public int maxContextTokens() {
        return maxContextTokens;
    }

    // ============ 限流 ============

    private void acquireRateLimit() {
        if (rateLimiter != null) {
            try {
                rateLimiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (minIntervalMs > 0) {
            long last = lastRequestTime.get();
            long now = System.currentTimeMillis();
            long elapsed = now - last;
            if (elapsed < minIntervalMs) {
                try {
                    Thread.sleep(minIntervalMs - elapsed);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestTime.set(System.currentTimeMillis());
        }
    }

    // ============ JSON 工具方法 ============

    /** 将 List<Map> 消息列表转为 JSON ArrayNode. */
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

    /** 安全获取 JSON 整数字段. */
    protected static int safeInt(JsonNode node, String field) {
        JsonNode val = node.get(field);
        if (val == null || val.isNull()) return 0;
        return val.asInt();
    }

    /** 安全获取 JSON 字符串字段. */
    protected static String safeStr(JsonNode node, String field) {
        JsonNode val = node.get(field);
        if (val == null || val.isNull()) return "";
        return val.asText();
    }

    // ============ Builder ============

    @SuppressWarnings("unchecked")
    public abstract static class Builder<T extends Builder<T>> {
        protected String modelId;
        protected int maxContextTokens = 128000;
        protected int connectTimeoutSeconds = 30;
        protected int readTimeoutSeconds = 120;
        protected int writeTimeoutSeconds = 30;
        protected int requestsPerMinute = 0; // 0 = unlimited

        public T modelId(String id) { this.modelId = id; return (T) this; }
        public T maxContextTokens(int tokens) { this.maxContextTokens = tokens; return (T) this; }
        public T connectTimeout(int seconds) { this.connectTimeoutSeconds = seconds; return (T) this; }
        public T readTimeout(int seconds) { this.readTimeoutSeconds = seconds; return (T) this; }
        public T requestsPerMinute(int rpm) { this.requestsPerMinute = rpm; return (T) this; }
    }

    // ============ 异常 ============

    public static class LLMException extends RuntimeException {
        private final int statusCode;

        public LLMException(String message) { super(message); this.statusCode = 0; }
        public LLMException(String message, int statusCode) { super(message); this.statusCode = statusCode; }
        public LLMException(String message, Throwable cause) { super(message, cause); this.statusCode = 0; }
        public LLMException(String message, int statusCode, Throwable cause) { super(message, cause); this.statusCode = statusCode; }

        public int statusCode() { return statusCode; }
        public boolean isRateLimit() { return statusCode == 429; }
        public boolean isRetryable() { return statusCode == 429 || statusCode >= 500; }
    }
}
