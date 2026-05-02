package io.sketch.mochaagents.llm.router;

import io.sketch.mochaagents.llm.LLM;
import java.util.List;

/**
 * 降级策略 — 主 LLM 不可用时自动切换到备用 LLM.
 */
public class FallbackStrategy {

    private final int maxRetries;

    public FallbackStrategy(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public FallbackStrategy() {
        this(3);
    }

    /**
     * 从备用列表选择降级 LLM.
     */
    public LLM fallback(LLM failed, List<LLM> alternatives) {
        for (LLM alt : alternatives) {
            if (alt != failed) return alt;
        }
        throw new IllegalStateException("No fallback LLM available");
    }
}
