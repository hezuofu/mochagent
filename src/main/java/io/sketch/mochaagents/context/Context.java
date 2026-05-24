// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.context;

import java.util.List;

/**
 * Agent execution context — manages the token window, stores chunks,
 * and compresses when the budget is exceeded.
 *
 * <p>{@link ContextManager} is the default implementation.
 * Use {@link #of(int)} or {@link #of(int, ContextStrategy, ContextCompressor)} to create one.
 */
public interface Context {

    /** Add a chunk to the context window. */
    void addChunk(ContextChunk chunk);

    /** Return the optimized context (strategy-filtered, within budget). */
    List<ContextChunk> getContext();

    /** Compress context to recover token budget. */
    void compress();

    /** Current token count. */
    int tokenCount();

    /** Maximum token budget. */
    int maxTokens();

    // ── Factory ──

    static Context of(int maxTokens) {
        return new ContextManager(maxTokens, (chunks, mt) -> chunks, null);
    }

    static Context of(int maxTokens, ContextStrategy strategy, ContextCompressor compressor) {
        return new ContextManager(maxTokens, strategy, compressor);
    }
}
