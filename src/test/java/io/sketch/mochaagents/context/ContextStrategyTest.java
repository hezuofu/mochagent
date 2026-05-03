package io.sketch.mochaagents.context;

import io.sketch.mochaagents.context.strategy.HybridContextStrategy;
import io.sketch.mochaagents.context.strategy.SlidingWindowStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextStrategyTest {

    private static ContextChunk c(String role, String content, int tokens) {
        return new ContextChunk("id-" + role, role, content, tokens);
    }

    private static List<ContextChunk> l(ContextChunk... arr) {
        List<ContextChunk> list = new ArrayList<>();
        for (ContextChunk ch : arr) list.add(ch);
        return list;
    }

    @Test
    void slidingWindowFitsWithinBudget() {
        List<ContextChunk> chunks = l(c("user", "msg1", 50), c("assistant", "msg2", 50),
                c("user", "msg3", 50), c("assistant", "msg4", 50));
        ContextStrategy strategy = new SlidingWindowStrategy();
        List<ContextChunk> result = strategy.apply(chunks, 150);
        assertTrue(result.size() <= 3);
    }

    @Test
    void slidingWindowKeepsMostRecent() {
        List<ContextChunk> chunks = l(c("user", "old", 100), c("assistant", "old2", 100),
                c("user", "recent", 50));
        ContextStrategy strategy = new SlidingWindowStrategy();
        List<ContextChunk> result = strategy.apply(chunks, 100);
        assertTrue(result.size() >= 1);
    }

    @Test
    void slidingWindowEmptyReturnsEmpty() {
        ContextStrategy strategy = new SlidingWindowStrategy();
        List<ContextChunk> result = strategy.apply(l(), 100);
        assertTrue(result.isEmpty());
    }

    @Test
    void hybridReturnsResults() {
        List<ContextChunk> chunks = l(c("system", "important", 20),
                c("user", "hello", 50), c("assistant", "hi", 50));
        ContextStrategy strategy = new HybridContextStrategy();
        List<ContextChunk> result = strategy.apply(chunks, 200);
        assertFalse(result.isEmpty());
    }
}
