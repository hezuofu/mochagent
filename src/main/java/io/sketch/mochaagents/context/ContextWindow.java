package io.sketch.mochaagents.context;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文窗口 — 管理上下文块的容量与 Token 计数.
 * @author lanxia39@163.com
 */
public class ContextWindow {

    private final int maxTokens;
    private final List<ContextChunk> chunks = new ArrayList<>();
    private int currentTokens;

    public ContextWindow(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public void add(ContextChunk chunk) {
        while (currentTokens + chunk.tokenCount() > maxTokens && !chunks.isEmpty()) {
            ContextChunk removed = chunks.remove(0);
            currentTokens -= removed.tokenCount();
        }
        chunks.add(chunk);
        currentTokens += chunk.tokenCount();
    }

    public List<ContextChunk> all() {
        return List.copyOf(chunks);
    }

    public int tokenCount() { return currentTokens; }
    public int maxTokens() { return maxTokens; }
    public int size() { return chunks.size(); }

    public void clear() {
        chunks.clear();
        currentTokens = 0;
    }
}
