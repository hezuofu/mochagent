// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.model;

import java.util.concurrent.CompletableFuture;

/**
 * Minimal Model — send a request, get a response.
 * Capabilities via {@code instanceof} checks, not interface pollution.
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

    default String modelName() { return "unknown"; }
    default int maxContextTokens() { return 128000; }

    default String modelFamily() {
        String n = modelName().toLowerCase();
        if (n.contains("claude")) return "anthropic";
        if (n.contains("gpt") || n.contains("codex")) return "openai";
        if (n.contains("gemini") || n.contains("gemma")) return "google";
        return "other";
    }

    /** Capability: supports native tool_use blocks (Anthropic, OpenAI). */
    interface NativeTools extends Model {
        default ModelResponse completeWithTools(ModelRequest request, java.util.List<io.sketch.mochaagents.tool.Tool> tools) {
            if (!request.hasTools()) return complete(request);
            return complete(request);
        }
    }

    /** Capability: supports streaming responses. */
    interface Streaming extends Model {
        StreamingResponse stream(ModelRequest request);
    }
}
