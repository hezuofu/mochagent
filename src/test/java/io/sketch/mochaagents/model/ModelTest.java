// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ModelRequest and ModelResponse builders.
 * @author lanxia39@163.com
 */
class ModelTest {

    // --- ModelRequest ---

    @Test
    void requestDefaultValues() {
        ModelRequest req = ModelRequest.builder().build();
        assertEquals(0.7, req.temperature(), 0.001);
        assertEquals(4096, req.maxTokens());
        assertEquals(1.0, req.topP(), 0.001);
        assertTrue(req.messages().isEmpty());
        assertTrue(req.stopSequences().isEmpty());
    }

    @Test
    void requestAddMessage() {
        ModelRequest req = ModelRequest.builder()
                .addMessage("system", "You are helpful.")
                .addMessage("user", "Hello")
                .build();

        assertEquals(2, req.messages().size());
        assertEquals("system", req.messages().get(0).get("role"));
        assertEquals("Hello", req.messages().get(1).get("content"));
    }

    @Test
    void requestMessagesAreImmutable() {
        ModelRequest req = ModelRequest.builder()
                .addMessage("user", "test")
                .build();

        List<Map<String, String>> messages = req.messages();
        assertThrows(UnsupportedOperationException.class,
                () -> messages.add(Map.of("role", "user", "content", "x")));
    }

    @Test
    void requestExtraParams() {
        ModelRequest req = ModelRequest.builder()
                .extraParams(Map.of("top_k", 40))
                .build();

        assertEquals(40, req.extraParams().get("top_k"));
    }

    // --- ModelResponse ---

    @Test
    void responseFactoryOf() {
        ModelResponse resp = ModelResponse.of("hello");
        assertEquals("hello", resp.content());
        assertEquals("unknown", resp.model());
        assertEquals(0, resp.promptTokens());
        assertEquals(0, resp.completionTokens());
    }

    @Test
    void responseTotalTokens() {
        ModelResponse resp = new ModelResponse("test", "gpt-4", 100, 50, 500, Map.of());
        assertEquals(150, resp.totalTokens());
        assertEquals(100, resp.promptTokens());
        assertEquals(50, resp.completionTokens());
        assertEquals(500, resp.latencyMs());
    }

    @Test
    void responseMetadataIsImmutable() {
        ModelResponse resp = ModelResponse.of("test");
        assertThrows(UnsupportedOperationException.class,
                () -> resp.metadata().put("key", "value"));
    }
}
