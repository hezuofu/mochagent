package io.sketch.mochaagents.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Agent interface default methods (functional composition).
 * @author lanxia39@163.com
 */
class AgentTest {

    // --- Simple test agent implementations ---

    static class EchoAgent implements Agent<String, String> {
        private final AgentMetadata meta;
        private final java.util.List<AgentListener<String, String>> listeners = new java.util.ArrayList<>();

        EchoAgent(String name) { this.meta = AgentMetadata.builder().name(name).build(); }

        @Override public String execute(String input) {
            listeners.forEach(l -> l.onStart(new AgentEvent<>(meta.name(), input)));
            String result = input;
            listeners.forEach(l -> l.onComplete(new AgentEvent<>(meta.name(), result)));
            return result;
        }
        @Override public CompletableFuture<String> executeAsync(String input) {
            return CompletableFuture.supplyAsync(() -> execute(input));
        }
        @Override public AgentMetadata metadata() { return meta; }
        @Override public void addListener(AgentListener<String, String> l) { listeners.add(l); }
        @Override public void removeListener(AgentListener<String, String> l) { listeners.remove(l); }
    }

    static class FailingAgent implements Agent<String, String> {
        private final AtomicBoolean called = new AtomicBoolean();
        FailingAgent() {}

        @Override public String execute(String input) {
            called.set(true);
            throw new RuntimeException("intentional failure");
        }
        @Override public CompletableFuture<String> executeAsync(String input) {
            return CompletableFuture.failedFuture(new RuntimeException("intentional failure"));
        }
        @Override public AgentMetadata metadata() { return AgentMetadata.builder().name("failing").build(); }
        @Override public void addListener(AgentListener<String, String> l) {}
        @Override public void removeListener(AgentListener<String, String> l) {}
        boolean wasCalled() { return called.get(); }
    }

    static class SlowAgent implements Agent<String, String> {
        private final long delayMs;
        SlowAgent(long delayMs) { this.delayMs = delayMs; }
        @Override public String execute(String input) {
            try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return input;
        }
        @Override public CompletableFuture<String> executeAsync(String input) {
            return CompletableFuture.supplyAsync(() -> execute(input));
        }
        @Override public AgentMetadata metadata() { return AgentMetadata.builder().name("slow").build(); }
        @Override public void addListener(AgentListener<String, String> l) {}
        @Override public void removeListener(AgentListener<String, String> l) {}
    }

    // --- before / after ---

    @Test
    void beforeTransformsInput() {
        Agent<String, String> agent = new EchoAgent("echo")
                .before((String s) -> "transformed: " + s);

        String result = agent.execute("hello");
        assertEquals("transformed: hello", result);
    }

    @Test
    void afterTransformsOutput() {
        Agent<String, Integer> agent = new EchoAgent("echo")
                .after(String::length);

        Integer result = agent.execute("hello");
        assertEquals(5, result);
    }

    // --- andThen (chaining) ---

    @Test
    void andThenChainsAgents() {
        Agent<String, String> upper = new EchoAgent("upper")
                .after(String::toUpperCase);

        Agent<String, Integer> pipeline = upper
                .andThen(new EchoAgent("len").after(String::length));

        Integer result = pipeline.execute("hello");
        assertEquals(5, result);
    }

    // --- when (conditional) ---

    @Test
    void whenConditionMatchesUsesAlternative() {
        Agent<String, String> normal = new EchoAgent("normal");
        Agent<String, String> alternative = new EchoAgent("alt")
                .after(s -> "ALT:" + s);

        Agent<String, String> agent = normal.when(s -> s.startsWith("!"), alternative);

        assertEquals("ALT:!critical", agent.execute("!critical"));
        assertEquals("ALT:!something", agent.execute("!something"));
    }

    @Test
    void whenConditionDoesNotMatchUsesSelf() {
        Agent<String, String> normal = new EchoAgent("normal");
        Agent<String, String> alternative = new EchoAgent("alt").after(s -> "ALT:" + s);

        Agent<String, String> agent = normal.when(s -> s.startsWith("!"), alternative);

        assertEquals("hello", agent.execute("hello"));
    }

    // --- withRetry ---

    @Test
    void withRetryRetriesAndEventuallyThrows() {
        FailingAgent failing = new FailingAgent();
        Agent<String, String> retrying = failing.withRetry(3);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> retrying.execute("test"));
        assertEquals("intentional failure", ex.getMessage());
        assertTrue(failing.wasCalled());
    }

    @Test
    void withRetrySucceedsOnFirstAttempt() {
        EchoAgent echo = new EchoAgent("echo");
        Agent<String, String> retrying = echo.withRetry(3);

        assertEquals("ok", retrying.execute("ok"));
    }

    // --- withTimeout (async) ---

    @Test
    void withTimeoutCompletesWithinTimeout() throws Exception {
        Agent<String, String> agent = new EchoAgent("echo").withTimeout(5000);

        CompletableFuture<String> future = agent.executeAsync("test");
        assertEquals("test", future.get(3, TimeUnit.SECONDS));
    }

    @Test
    void withTimeoutThrowsOnSlowExecution() {
        Agent<String, String> withTimeout = new SlowAgent(1000).withTimeout(100);

        CompletableFuture<String> future = withTimeout.executeAsync("test");
        assertThrows(ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
    }

    // --- listener ---

    @Test
    void listenersAreNotified() {
        EchoAgent agent = new EchoAgent("echo");
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();

        agent.addListener(new AgentListener<>() {
            @Override public void onStart(AgentEvent<String> e) { started.set(true); }
            @Override public void onComplete(AgentEvent<String> e) { completed.set(true); }
            @Override public void onError(AgentEvent<Throwable> e) {}
        });

        agent.execute("test");
        assertTrue(started.get());
        assertTrue(completed.get());
    }

    // --- metadata ---

    @Test
    void metadataReturnsName() {
        EchoAgent agent = new EchoAgent("test-agent");
        assertEquals("test-agent", agent.metadata().name());
    }
}
