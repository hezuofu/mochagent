package io.sketch.mochaagents.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文管理器 — 统一管理上下文块的增删、压缩与策略执行.
 */
public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    private final ContextWindow window;
    private final ContextStrategy strategy;
    private final ContextCompressor compressor;

    public ContextManager(int maxTokens, ContextStrategy strategy, ContextCompressor compressor) {
        this.window = new ContextWindow(maxTokens);
        this.strategy = strategy;
        this.compressor = compressor;
        log.debug("ContextManager created: maxTokens={}, strategy={}, compressor={}",
                maxTokens, strategy.getClass().getSimpleName(),
                compressor != null ? compressor.getClass().getSimpleName() : "none");
    }

    public void addChunk(ContextChunk chunk) {
        window.add(chunk);
        log.debug("Context chunk added: {}, total chunks={}", chunk.id(), window.all().size());
    }

    public List<ContextChunk> getContext() {
        List<ContextChunk> result = strategy.apply(window.all(), window.maxTokens());
        log.debug("Context retrieved: {} chunks (from {}), tokens={}/{}",
                result.size(), window.all().size(), window.tokenCount(), window.maxTokens());
        return result;
    }

    public void compress() {
        if (compressor == null) return;
        int beforeSize = window.all().size();
        List<ContextChunk> compressed = compressor.compress(window.all(), window.maxTokens());
        window.clear();
        for (ContextChunk chunk : compressed) {
            window.add(chunk);
        }
        log.debug("Context compressed: {} -> {} chunks", beforeSize, compressed.size());
    }

    public void clear() {
        log.debug("Context cleared");
        window.clear();
    }

    public int tokenCount() { return window.tokenCount(); }
    public int maxTokens() { return window.maxTokens(); }
}
