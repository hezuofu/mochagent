package io.sketch.mochaagents.llm.router;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 路由器 — 根据请求特征（复杂度、成本、延迟）智能路由到最合适的 LLM.
 */
public class LLMRouter {

    private final Map<String, LLM> providers = new ConcurrentHashMap<>();
    private final CostOptimizer costOptimizer;
    private final FallbackStrategy fallbackStrategy;

    public LLMRouter(CostOptimizer costOptimizer, FallbackStrategy fallbackStrategy) {
        this.costOptimizer = costOptimizer;
        this.fallbackStrategy = fallbackStrategy;
    }

    public LLMRouter() {
        this(new CostOptimizer(), new FallbackStrategy());
    }

    /** 注册 LLM 提供者 */
    public void register(String name, LLM llm) {
        providers.put(name, llm);
    }

    /** 路由请求到最佳 LLM */
    public LLM route(LLMRequest request) {
        // 简单路由：选择第一个可用的，或基于成本优化
        if (providers.isEmpty()) throw new IllegalStateException("No LLM providers registered");

        List<LLM> candidates = new ArrayList<>(providers.values());
        return costOptimizer.select(candidates, request);
    }

    /** 带降级的路由 */
    public LLM routeWithFallback(LLMRequest request) {
        LLM primary = route(request);
        try {
            return primary;
        } catch (Exception e) {
            return fallbackStrategy.fallback(primary, new ArrayList<>(providers.values()));
        }
    }

    /** 获取所有提供者 */
    public Collection<LLM> getProviders() { return providers.values(); }
}
