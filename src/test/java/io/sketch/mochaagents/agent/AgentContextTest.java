package io.sketch.mochaagents.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AgentContext builder and immutability.
 * @author lanxia39@163.com
 */
class AgentContextTest {

    @Test
    void buildMinimal() {
        AgentContext ctx = AgentContext.builder()
                .sessionId("s1")
                .userId("u1")
                .userMessage("hello")
                .build();

        assertEquals("s1", ctx.sessionId());
        assertEquals("u1", ctx.userId());
        assertEquals("hello", ctx.userMessage());
        assertNotNull(ctx.timestamp());
    }

    @Test
    void buildFull() {
        Instant now = Instant.now();
        AgentContext ctx = AgentContext.builder()
                .sessionId("s1")
                .userId("u1")
                .userMessage("hello")
                .conversationHistory("previous messages")
                .metadata("source", "cli")
                .timestamp(now)
                .build();

        assertEquals("previous messages", ctx.conversationHistory());
        assertEquals("cli", ctx.metadata().get("source"));
        assertEquals(now, ctx.timestamp());
    }

    @Test
    void metadataIsImmutable() {
        AgentContext ctx = AgentContext.builder()
                .sessionId("s1")
                .userId("u1")
                .userMessage("hello")
                .metadata("key", "value")
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> ctx.metadata().put("new", "val"));
    }

    @Test
    void timestampDefaultsToNow() {
        Instant before = Instant.now();
        AgentContext ctx = AgentContext.builder()
                .sessionId("s1").userId("u1").userMessage("m").build();
        Instant after = Instant.now();

        assertFalse(ctx.timestamp().isBefore(before));
        assertFalse(ctx.timestamp().isAfter(after));
    }

    @Test
    void builderReuseCreatesIndependentInstances() {
        AgentContext.Builder builder = AgentContext.builder()
                .sessionId("s1")
                .userId("u1");

        AgentContext ctx1 = builder.userMessage("msg1").build();
        AgentContext ctx2 = builder.userMessage("msg2").build();

        assertEquals("msg1", ctx1.userMessage());
        assertEquals("msg2", ctx2.userMessage());
    }
}
