package io.sketch.mochaagents.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Response-caching LLM decorator — caches identical requests to save cost.
 * <p>LRU cache with configurable max size. Cache key = hash of messages + parameters.
 * @author lanxia39@163.com
 */
public class CachingLLM implements LLM {

    private static final Logger log = LoggerFactory.getLogger(CachingLLM.class);

    private final LLM delegate;
    private final CostTracker costTracker;
    private final int maxCacheSize;
    private final Map<String, LLMResponse> cache;

    public CachingLLM(LLM delegate, CostTracker costTracker, int maxCacheSize) {
        this.delegate = delegate;
        this.costTracker = costTracker;
        this.maxCacheSize = maxCacheSize;
        this.cache = new LinkedHashMap<>(maxCacheSize, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, LLMResponse> e) {
                return size() > maxCacheSize;
            }
        };
    }

    public CachingLLM(LLM delegate) { this(delegate, new CostTracker(), 100); }

    @Override
    public LLMResponse complete(LLMRequest request) {
        String key = cacheKey(request);
        LLMResponse cached = cache.get(key);
        if (cached != null) {
            log.debug("Cache hit: saved {} in + {} out tokens",
                    cached.promptTokens(), cached.completionTokens());
            return cached;
        }

        long start = System.currentTimeMillis();
        LLMResponse response = delegate.complete(request);
        long latency = System.currentTimeMillis() - start;

        cache.put(key, response);
        costTracker.record(delegate.modelName(), response.promptTokens(), response.completionTokens());
        log.debug("LLM call: {}ms, {} in + {} out tokens, total cost=${}",
                latency, response.promptTokens(), response.completionTokens(),
                String.format("%.4f", costTracker.estimatedTotalCost()));
        return response;
    }

    @Override
    public CompletableFuture<LLMResponse> completeAsync(LLMRequest request) {
        return CompletableFuture.supplyAsync(() -> complete(request));
    }

    @Override
    public StreamingResponse stream(LLMRequest request) {
        return delegate.stream(request); // streaming not cached
    }

    @Override public String modelName() { return delegate.modelName(); }
    @Override public int maxContextTokens() { return delegate.maxContextTokens(); }

    /** Access the cost tracker for usage reports. */
    public CostTracker costTracker() { return costTracker; }

    /** Generate usage cost report. */
    public String report() { return costTracker.report(); }

    /** Clear the cache. */
    public void clearCache() { cache.clear(); }

    /** Number of cached entries. */
    public int cacheSize() { return cache.size(); }

    private static String cacheKey(LLMRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.temperature()).append("|").append(request.maxTokens());
        if (request.messages() != null) {
            for (var m : request.messages())
                sb.append("|").append(m.get("role")).append(":").append(m.get("content"));
        }
        if (request.prompt() != null) sb.append("|").append(request.prompt());
        // Simple hash — use content length as lightweight key
        return Integer.toHexString(sb.toString().hashCode()) + ":" + sb.length();
    }
}
