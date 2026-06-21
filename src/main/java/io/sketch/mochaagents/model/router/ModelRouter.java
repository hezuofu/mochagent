// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.model.router;

import io.sketch.mochaagents.MochaException;
import io.sketch.mochaagents.model.Model;
import io.sketch.mochaagents.model.ModelRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Model router — health checks, circuit breaker, cost+latency load balancing.
 *
 * @author lanxia39@163.com
 */
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);
    private static final int CIRCUIT_BREAKER_THRESHOLD = 3;
    private static final long CIRCUIT_HALF_OPEN_MS = 30_000;

    private final Map<String, Provider> providers = new ConcurrentHashMap<>();
    private final CostOptimizer costOptimizer;

    public ModelRouter(CostOptimizer costOptimizer) { this.costOptimizer = costOptimizer; }
    public ModelRouter() { this(new CostOptimizer()); }

    public void register(String name, Model model) {
        providers.put(name, new Provider(name, model));
    }

    public void unregister(String name) { providers.remove(name); }

    /** Route to best provider — cost + latency weighted. */
    public Model route(ModelRequest request) {
        if (providers.isEmpty()) {
            throw new MochaException.ConfigException("No providers");
        }

        List<Provider> healthy = providers.values().stream()
                .filter(Provider::isHealthy)
                .toList();

        if (healthy.isEmpty()) {
            // All dead — try half-open
            healthy = providers.values().stream()
                    .filter(p -> p.state == ProviderState.HALF_OPEN)
                    .toList();
        }
        if (healthy.isEmpty()) {
            throw new MochaException.ConfigException("All providers unhealthy");
        }

        return costOptimizer.select(
                healthy.stream().map(p -> p.model).toList(), request);
    }

    /** Record successful call — updates latency stats. */
    public void recordSuccess(String providerName, long latencyMs) {
        Provider p = providers.get(providerName);
        if (p != null) p.recordSuccess(latencyMs);
    }

    /** Record failure — may trip circuit breaker. */
    public void recordFailure(String providerName) {
        Provider p = providers.get(providerName);
        if (p != null) {
            p.recordFailure();
        }
    }

    // ── Getters ──

    public Collection<Model> getProviders() {
        return providers.values().stream().map(p -> p.model).toList();
    }

    /** Health status for monitoring. */
    public Map<String, ProviderState> health() {
        Map<String, ProviderState> result = new LinkedHashMap<>();
        providers.forEach((n, p) -> result.put(n, p.state));
        return result;
    }

    public Map<String, Long> avgLatency() {
        Map<String, Long> result = new LinkedHashMap<>();
        providers.forEach((n, p) -> result.put(n, p.avgLatency.get()));
        return result;
    }

    // ── Inner ──

    enum ProviderState { HEALTHY, DEGRADED, HALF_OPEN, DEAD }

    static class Provider {
        final String name;
        final Model model;
        volatile ProviderState state = ProviderState.HEALTHY;
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        final AtomicLong avgLatency = new AtomicLong();
        volatile long openedAt;

        Provider(String name, Model model) { this.name = name; this.model = model; }

        boolean isHealthy() { return state == ProviderState.HEALTHY || state == ProviderState.DEGRADED; }

        void recordSuccess(long latencyMs) {
            consecutiveFailures.set(0);
            avgLatency.set((avgLatency.get() + latencyMs) / 2);
            if (state == ProviderState.HALF_OPEN) {
                state = ProviderState.HEALTHY;
                log.info("Provider {} recovered", name);
            }
        }

        void recordFailure() {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= CIRCUIT_BREAKER_THRESHOLD && state == ProviderState.HEALTHY) {
                state = ProviderState.DEAD;
                openedAt = System.currentTimeMillis();
                log.warn("Provider {} circuit OPEN after {} failures", name, failures);
            } else if (state == ProviderState.HALF_OPEN) {
                state = ProviderState.DEAD;
                openedAt = System.currentTimeMillis();
                log.warn("Provider {} half-open failed, circuit OPEN", name);
            }

            // Try half-open after cooldown
            if (state == ProviderState.DEAD
                    && System.currentTimeMillis() - openedAt > CIRCUIT_HALF_OPEN_MS) {
                state = ProviderState.HALF_OPEN;
                log.info("Provider {} circuit HALF_OPEN", name);
            }
        }
    }
}
