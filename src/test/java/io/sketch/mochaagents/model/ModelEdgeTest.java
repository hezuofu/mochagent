// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelEdgeTest {

    // --- ModelRequest edge cases ---

    @Test
    void requestWithZeroMaxTokens() {
        ModelRequest req = ModelRequest.builder().maxTokens(0).build();
        assertEquals(0, req.maxTokens());
    }

    @Test
    void requestWithNegativeTemperature() {
        ModelRequest req = ModelRequest.builder().temperature(-0.5).build();
        assertEquals(-0.5, req.temperature(), 0.001);
    }

    @Test
    void requestDefaultPromptIsEmpty() {
        ModelRequest req = ModelRequest.builder().build();
        assertEquals("", req.prompt());
    }

    @Test
    void requestMessagesAreCopied() {
        ModelRequest.Builder builder = ModelRequest.builder();
        builder.addMessage("user", "hello");
        ModelRequest req = builder.build();

        List<Map<String, String>> msgs = req.messages();
        assertEquals(1, msgs.size());
        assertThrows(UnsupportedOperationException.class,
                () -> msgs.add(Map.of("role", "user", "content", "x")));
    }

    @Test
    void requestExtraParamsAreImmutable() {
        ModelRequest req = ModelRequest.builder()
                .extraParams(Map.of("key", "value"))
                .build();
        assertThrows(UnsupportedOperationException.class,
                () -> req.extraParams().put("new", "val"));
    }

    @Test
    void requestStopSequencesAreImmutable() {
        ModelRequest req = ModelRequest.builder()
                .stopSequences(List.of("\n"))
                .build();
        assertThrows(UnsupportedOperationException.class,
                () -> req.stopSequences().add("x"));
    }

    // --- ModelResponse edge cases ---

    @Test
    void responseOfEmptyString() {
        ModelResponse resp = ModelResponse.of("");
        assertEquals("", resp.content());
        assertEquals("unknown", resp.model());
        assertEquals(0, resp.totalTokens());
    }

    @Test
    void responseOfNull() {
        ModelResponse resp = ModelResponse.of(null);
        assertNull(resp.content());
    }

    @Test
    void responseWithZeroTokens() {
        ModelResponse resp = new ModelResponse("test", "gpt-4", 0, 0, 0, Map.of());
        assertEquals(0, resp.totalTokens());
        assertEquals(0, resp.promptTokens());
        assertEquals(0, resp.completionTokens());
    }

    @Test
    void responseMetadataImmutable() {
        ModelResponse resp = ModelResponse.of("test");
        assertThrows(UnsupportedOperationException.class,
                () -> resp.metadata().put("key", "value"));
    }

    // --- StreamingResponse edge cases ---

    @Test
    void streamingResponseInitiallyNotComplete() throws Exception {
        StreamingResponse stream = new StreamingResponse();
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean errored = new java.util.concurrent.atomic.AtomicBoolean();

        stream.push("hello");
        stream.complete();

        stream.subscribe(
                token -> {}, // ignore token
                err -> errored.set(true),
                () -> completed.set(true));

        Thread.sleep(200);
        assertTrue(completed.get());
        assertFalse(errored.get());
    }

    @Test
    void streamingResponseWithError() throws Exception {
        StreamingResponse stream = new StreamingResponse();
        java.util.concurrent.atomic.AtomicBoolean errored = new java.util.concurrent.atomic.AtomicBoolean();

        stream.error(new RuntimeException("test error"));
        stream.subscribe(
                token -> {},
                err -> errored.set(true),
                () -> {});

        Thread.sleep(200);
        assertTrue(errored.get());
    }
}
