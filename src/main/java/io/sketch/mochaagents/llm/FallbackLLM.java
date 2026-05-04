package io.sketch.mochaagents.llm;

import java.util.concurrent.CompletableFuture;

/** Default LLM when no API key is configured. Returns helpful message. */
public class FallbackLLM implements LLM {
    @Override public LLMResponse complete(LLMRequest r) { return LLMResponse.of("No LLM configured. Use --model flag or set API key."); }
    @Override public CompletableFuture<LLMResponse> completeAsync(LLMRequest r) { return CompletableFuture.completedFuture(complete(r)); }
    @Override public StreamingResponse stream(LLMRequest r) { throw new UnsupportedOperationException(); }
    @Override public String modelName() { return "fallback"; }
    @Override public int maxContextTokens() { return 4096; }
}
