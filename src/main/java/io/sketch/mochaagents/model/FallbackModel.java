// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.model;

import java.util.concurrent.CompletableFuture;

/** Default Model when no API key is configured. Returns helpful message.  * @author lanxia39@163.com
 */
public class FallbackModel implements Model {
    @Override public ModelResponse complete(ModelRequest r) { return ModelResponse.of("No Model configured. Use --model flag or set API key."); }
    @Override public CompletableFuture<ModelResponse> completeAsync(ModelRequest r) { return CompletableFuture.completedFuture(complete(r)); }
    @Override public StreamingResponse stream(ModelRequest r) { throw new UnsupportedOperationException(); }
    @Override public String modelName() { return "fallback"; }
    @Override public int maxContextTokens() { return 4096; }
}
