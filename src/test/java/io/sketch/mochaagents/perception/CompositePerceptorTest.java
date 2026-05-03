package io.sketch.mochaagents.perception;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class CompositePerceptorTest {

    private static final class MockPerceptor implements Perceptor<String, Observation> {
        private final String label;
        MockPerceptor(String label) { this.label = label; }

        @Override public PerceptionResult<Observation> perceive(String input) {
            return PerceptionResult.of(
                    new Observation(label, input + " from " + label, "mock"), "mock", 0.8);
        }
        @Override public CompletableFuture<PerceptionResult<Observation>> perceiveAsync(String input) {
            return CompletableFuture.completedFuture(perceive(input));
        }
    }

    @Test
    void compositeMergesMultipleSensors() {
        CompositePerceptor<String, Observation> comp = new CompositePerceptor<>(
                new MockPerceptor("sensorA"), new MockPerceptor("sensorB"));

        PerceptionResult<Observation> result = comp.perceive("input");
        assertNotNull(result.data());
        assertEquals("composite", result.type());
        assertTrue(result.confidence() > 0);
    }

    @Test
    void compositeHandlesSingleSensor() {
        CompositePerceptor<String, Observation> comp = new CompositePerceptor<>(
                new MockPerceptor("solo"));

        PerceptionResult<Observation> result = comp.perceive("test");
        assertNotNull(result);
        assertEquals(1, comp.sensorCount());
    }

    @Test
    void compositeSensorCount() {
        CompositePerceptor<String, Observation> comp = new CompositePerceptor<>(
                new MockPerceptor("a"), new MockPerceptor("b"), new MockPerceptor("c"));
        assertEquals(3, comp.sensorCount());
    }
}
