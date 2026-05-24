// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.perception;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Minimal perception — observe input and return structured result.
 */
@FunctionalInterface
public interface Perceptor<I, O> {

    PerceptionResult<O> perceive(I input);

    default CompletableFuture<PerceptionResult<O>> perceiveAsync(I input) {
        return CompletableFuture.supplyAsync(() -> perceive(input));
    }

    default Perceptor<I, O> filter(Predicate<O> test) {
        return input -> {
            PerceptionResult<O> r = perceive(input);
            if (r.data() != null && !test.test(r.data())) return PerceptionResult.empty();
            return r;
        };
    }
}
