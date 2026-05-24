// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.model;

import io.sketch.mochaagents.model.Model;
import io.sketch.mochaagents.model.ModelRequest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Model 路由器 — 根据请求特征（复杂度、成本、延迟）智能路由到最合适的 Model.
 * @author lanxia39@163.com
 */
public class ModelRouter {

    private final Map<String, Model> providers = new ConcurrentHashMap<>();
    private final CostOptimizer costOptimizer;
    private final FallbackStrategy fallbackStrategy;

    public ModelRouter(CostOptimizer costOptimizer, FallbackStrategy fallbackStrategy) {
        this.costOptimizer = costOptimizer;
        this.fallbackStrategy = fallbackStrategy;
    }

    public ModelRouter() {
        this(new CostOptimizer(), new FallbackStrategy());
    }

    /** 注册 Model 提供者 */
    public void register(String name, Model model) {
        providers.put(name, model);
    }

    /** 路由请求到最佳 Model */
    public Model route(ModelRequest request) {
        // 简单路由：选择第一个可用的，或基于成本优化
        if (providers.isEmpty()) throw new IllegalStateException("No Model providers registered");

        List<Model> candidates = new ArrayList<>(providers.values());
        return costOptimizer.select(candidates, request);
    }

    /** 带降级的路由 */
    public Model routeWithFallback(ModelRequest request) {
        Model primary = route(request);
        try {
            return primary;
        } catch (Exception e) {
            return fallbackStrategy.fallback(primary, new ArrayList<>(providers.values()));
        }
    }

    /** 获取所有提供者 */
    public Collection<Model> getProviders() { return providers.values(); }
}
