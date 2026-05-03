package io.sketch.mochaagents.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LLMEdgeTest {

    // --- LLMRequest edge cases ---

    @Test
    void requestWithZeroMaxTokens() {
        LLMRequest req = LLMRequest.builder().maxTokens(0).build();
        assertEquals(0, req.maxTokens());
    }

    @Test
    void requestWithNegativeTemperature() {
        LLMRequest req = LLMRequest.builder().temperature(-0.5).build();
        assertEquals(-0.5, req.temperature(), 0.001);
    }

    @Test
    void requestDefaultPromptIsEmpty() {
        LLMRequest req = LLMRequest.builder().build();
        assertEquals("", req.prompt());
    }

    @Test
    void requestMessagesAreCopied() {
        LLMRequest.Builder builder = LLMRequest.builder();
        builder.addMessage("user", "hello");
        LLMRequest req = builder.build();

        List<Map<String, String>> msgs = req.messages();
        assertEquals(1, msgs.size());
        assertThrows(UnsupportedOperationException.class,
                () -> msgs.add(Map.of("role", "user", "content", "x")));
    }

    @Test
    void requestExtraParamsAreImmutable() {
        LLMRequest req = LLMRequest.builder()
                .extraParams(Map.of("key", "value"))
                .build();
        assertThrows(UnsupportedOperationException.class,
                () -> req.extraParams().put("new", "val"));
    }

    @Test
    void requestStopSequencesAreImmutable() {
        LLMRequest req = LLMRequest.builder()
                .stopSequences(List.of("\n"))
                .build();
        assertThrows(UnsupportedOperationException.class,
                () -> req.stopSequences().add("x"));
    }

    // --- LLMResponse edge cases ---

    @Test
    void responseOfEmptyString() {
        LLMResponse resp = LLMResponse.of("");
        assertEquals("", resp.content());
        assertEquals("unknown", resp.model());
        assertEquals(0, resp.totalTokens());
    }

    @Test
    void responseOfNull() {
        LLMResponse resp = LLMResponse.of(null);
        assertNull(resp.content());
    }

    @Test
    void responseWithZeroTokens() {
        LLMResponse resp = new LLMResponse("test", "gpt-4", 0, 0, 0, Map.of());
        assertEquals(0, resp.totalTokens());
        assertEquals(0, resp.promptTokens());
        assertEquals(0, resp.completionTokens());
    }

    @Test
    void responseMetadataImmutable() {
        LLMResponse resp = LLMResponse.of("test");
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
