// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.llm;

import java.util.concurrent.CompletableFuture;

/**
 * Minimal LLM — send a request, get a response.
 */
@FunctionalInterface
public interface LLM {

    LLMResponse complete(LLMRequest request);

    default CompletableFuture<LLMResponse> completeAsync(LLMRequest request) {
        return CompletableFuture.supplyAsync(() -> complete(request));
    }

    default StreamingResponse stream(LLMRequest request) {
        throw new UnsupportedOperationException("streaming not supported by " + modelName());
    }

    default String modelName() { return "unknown"; }
    default int maxContextTokens() { return 128000; }
}
