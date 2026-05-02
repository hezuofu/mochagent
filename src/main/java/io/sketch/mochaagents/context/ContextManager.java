package io.sketch.mochaagents.context;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文管理器 — 统一管理上下文块的增删、压缩与策略执行.
 */
public class ContextManager {

    private final ContextWindow window;
    private final ContextStrategy strategy;
    private final ContextCompressor compressor;

    public ContextManager(int maxTokens, ContextStrategy strategy, ContextCompressor compressor) {
        this.window = new ContextWindow(maxTokens);
        this.strategy = strategy;
        this.compressor = compressor;
    }

    public void addChunk(ContextChunk chunk) {
        window.add(chunk);
    }

    public List<ContextChunk> getContext() {
        return strategy.apply(window.all(), window.maxTokens());
    }

    public void compress() {
        if (compressor == null) return;
        List<ContextChunk> compressed = compressor.compress(window.all(), window.maxTokens());
        window.clear();
        for (ContextChunk chunk : compressed) {
            window.add(chunk);
        }
    }

    public void clear() {
        window.clear();
    }

    public int tokenCount() { return window.tokenCount(); }
    public int maxTokens() { return window.maxTokens(); }
}
