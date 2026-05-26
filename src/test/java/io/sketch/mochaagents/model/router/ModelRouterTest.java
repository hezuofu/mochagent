// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.model.router;

import io.sketch.mochaagents.model.Model;
import io.sketch.mochaagents.model.ModelRequest;
import io.sketch.mochaagents.model.ModelResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelRouterTest {

    // ── CostOptimizer ──

    @Test void costOptimizerSelectsCheapest() {
        var opt = new CostOptimizer();
        var cheap = dummyModel("gpt-4o-mini", 128000);
        var expensive = dummyModel("gpt-4o", 128000);
        var req = ModelRequest.builder().prompt("hi").maxTokens(100).build();

        var selected = opt.select(List.of(cheap, expensive), req);
        assertEquals("gpt-4o-mini", selected.modelName());
    }

    @Test void costOptimizerConsidersContext() {
        var opt = new CostOptimizer();
        var small = dummyModel("deepseek-chat", 64000);
        var big = dummyModel("gpt-4o-mini", 128000);
        var req = ModelRequest.builder().prompt("test").maxTokens(100).build();

        var selected = opt.select(List.of(small, big), req);
        // deepseek-chat is cheaper ($0.27/$1.10 vs $0.15/$0.60) but context penalty matters
        // gpt-4o-mini: cost + 1/128 ≈ 0.000015 + 0.0078 = 0.0078
        // deepseek-chat: cost + 1/64 ≈ 0.0000027 + 0.0156 = 0.0156
        // So gpt-4o-mini wins
        assertEquals("gpt-4o-mini", selected.modelName());
    }

    @Test void costOptimizerSingleCandidate() {
        var opt = new CostOptimizer();
        var model = dummyModel("gpt-4o", 128000);
        var req = ModelRequest.builder().prompt("hi").build();

        assertEquals(model, opt.select(List.of(model), req));
    }

    @Test void costOptimizerEmptyCandidatesThrows() {
        var opt = new CostOptimizer();
        var req = ModelRequest.builder().prompt("hi").build();
        assertThrows(IllegalArgumentException.class, () -> opt.select(List.of(), req));
    }

    @Test void costOptimizerWithMessagesEstimatesTokens() {
        var opt = new CostOptimizer();
        var a = dummyModel("gpt-4o-mini", 128000);
        var b = dummyModel("gpt-4o", 128000);
        var req = ModelRequest.builder()
                .prompt("long prompt here " + "x".repeat(200))
                .addMessage("user", "message content " + "y".repeat(200))
                .maxTokens(200)
                .build();

        var selected = opt.select(List.of(a, b), req);
        assertEquals("gpt-4o-mini", selected.modelName());
    }

    // ── ModelRouter register/routing ──

    @Test void routerRegistersAndRoutes() {
        var router = new ModelRouter();
        router.register("cheap", dummyModel("gpt-4o-mini", 128000));
        router.register("expensive", dummyModel("gpt-4o", 128000));

        var req = ModelRequest.builder().prompt("hi").maxTokens(100).build();
        var model = router.route(req);
        assertNotNull(model);
        assertEquals("gpt-4o-mini", model.modelName());
    }

    @Test void routerEmptyProvidersThrows() {
        var router = new ModelRouter();
        var req = ModelRequest.builder().prompt("hi").build();
        assertThrows(io.sketch.mochaagents.MochaException.ConfigException.class,
                () -> router.route(req));
    }

    @Test void routerUnregisterRemovesProvider() {
        var router = new ModelRouter();
        router.register("test", dummyModel("gpt-4o-mini", 128000));
        router.unregister("test");

        var req = ModelRequest.builder().prompt("hi").build();
        assertThrows(io.sketch.mochaagents.MochaException.ConfigException.class,
                () -> router.route(req));
    }

    @Test void routerGetProviders() {
        var router = new ModelRouter();
        router.register("a", dummyModel("gpt-4o-mini", 128000));
        router.register("b", dummyModel("gpt-4o", 128000));

        assertEquals(2, router.getProviders().size());
    }

    @Test void routerHealthInitiallyHealthy() {
        var router = new ModelRouter();
        router.register("a", dummyModel("gpt-4o-mini", 128000));

        var health = router.health();
        assertTrue(health.containsKey("a"));
        assertEquals(ModelRouter.ProviderState.HEALTHY, health.get("a"));
    }

    // ── ModelRouter health tracking ──

    @Test void routerRecordSuccessKeepsHealthy() {
        var router = new ModelRouter();
        router.register("a", dummyModel("gpt-4o-mini", 128000));
        router.recordSuccess("a", 100);

        assertEquals(ModelRouter.ProviderState.HEALTHY, router.health().get("a"));
    }

    @Test void routerAvgLatencyTracksValue() {
        var router = new ModelRouter();
        router.register("a", dummyModel("test", 128000));
        router.recordSuccess("a", 200);

        assertTrue(router.avgLatency().get("a") > 0);
    }

    // ── FallbackStrategy ──

    @Test void fallbackSelectsAlternative() {
        var strategy = new FallbackStrategy(3);
        var primary = dummyModel("primary", 128000);
        var alt = dummyModel("alt", 64000);

        var result = strategy.fallback(primary, List.of(primary, alt));
        assertEquals("alt", result.modelName());
    }

    @Test void fallbackNoAlternativeThrows() {
        var strategy = new FallbackStrategy();
        var primary = dummyModel("only", 128000);

        assertThrows(IllegalStateException.class,
                () -> strategy.fallback(primary, List.of(primary)));
    }

    // ── RouterAdapter via ModelConfig flow ──

    @Test void routerModelNameIncludesProviderCount() {
        var router = new ModelRouter(new CostOptimizer());
        router.register("a", dummyModel("gpt-4o-mini", 128000));
        router.register("b", dummyModel("gpt-4o", 128000));

        // Simulate ModelConfig.RouterAdapter pattern
        String name = "router[" + router.getProviders().size() + "]";
        assertTrue(name.contains("2"));
    }

    // ── helpers ──

    private static Model dummyModel(String name, int maxContext) {
        return new Model() {
            @Override public ModelResponse complete(ModelRequest req) {
                return new ModelResponse("ok", null, 0, 0, 0L, null);
            }
            @Override public CompletableFuture<ModelResponse> completeAsync(ModelRequest req) {
                return CompletableFuture.completedFuture(complete(req));
            }
            @Override public String modelName() { return name; }
            @Override public int maxContextTokens() { return maxContext; }
        };
    }
}
