// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.model;

import io.sketch.mochaagents.tool.Tool;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal Model — send a request, get a response.
 *
 * @author lanxia39@163.com
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

    /** Whether this model supports native tool_use blocks (not text-parsed). */
    default boolean supportsNativeTools() { return false; }

    /** Complete with tool schemas passed to the API for native tool calling. */
    default ModelResponse completeWithTools(ModelRequest request, List<Tool> tools) {
        return complete(request); // default: ignore tools, use text parsing
    }

    default String modelName() { return "unknown"; }
    default int maxContextTokens() { return 128000; }

    default String modelFamily() {
        String n = modelName().toLowerCase();
        if (n.contains("claude")) return "anthropic";
        if (n.contains("gpt") || n.contains("codex")) return "openai";
        if (n.contains("gemini") || n.contains("gemma")) return "google";
        return "other";
    }
}
