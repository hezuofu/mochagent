// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.model;

import java.util.concurrent.CompletableFuture;

/**
 * Minimal Model — send a request, get a response.
 */
@FunctionalInterface
public interface Model {

    ModelResponse complete(ModelRequest request);

    default CompletableFuture<ModelResponse> completeAsync(ModelRequest request) {
        return CompletableFuture.supplyAsync(() -> complete(request));
    }

    default StreamingResponse stream(ModelRequest request) {
        throw new UnsupportedOperationException("streaming not supported by " + modelName());
    }

    default String modelName() { return "unknown"; }
    default int maxContextTokens() { return 128000; }
}
