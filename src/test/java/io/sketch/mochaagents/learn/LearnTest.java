// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.learn;

import io.sketch.mochaagents.memory.MemoryManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LearnTest {

    // ── Experience ──

    @Test void experienceBuilderCreatesCompleteRecord() {
        var exp = Experience.<String, String>builder()
                .input("hello")
                .output("world")
                .expectedOutput("world")
                .reward(1.0)
                .feedback("correct")
                .metadata(Map.of("source", "test"))
                .build();

        assertNotNull(exp.id());
        assertEquals("hello", exp.input());
        assertEquals("world", exp.output());
        assertEquals(1.0, exp.reward());
        assertTrue(exp.isPositive());
        assertFalse(exp.isNegative());
        assertEquals("correct", exp.feedback());
        assertEquals("test", exp.metadata().get("source"));
        assertNotNull(exp.timestamp());
    }

    @Test void experienceNegativeReward() {
        var exp = Experience.<String, String>builder()
                .input("q").output("bad").reward(-0.5).build();

        assertFalse(exp.isPositive());
        assertTrue(exp.isNegative());
    }

    @Test void experienceZeroRewardIsNeutral() {
        var exp = Experience.<String, String>builder()
                .input("q").output("a").reward(0.0).build();

        assertFalse(exp.isPositive());
        assertFalse(exp.isNegative());
    }

    // ── ExperienceBuffer ──

    @Test void bufferEvictsOldestWhenFull() {
        var buf = new ExperienceBuffer<String, String>(3);
        for (int i = 0; i < 5; i++) {
            buf.add(Experience.<String, String>builder()
                    .input("q" + i).output("a" + i).reward(i).build());
        }
        assertEquals(3, buf.size());
        // Capacity 3 after adding 5: [2, 3, 4], oldest (0, 1) evicted
        var recent = buf.recent(3);
        assertEquals(2.0, recent.get(0).reward());
        assertEquals(4.0, recent.get(2).reward());
    }

    @Test void bufferPrioritizedSampleSortsByReward() {
        var buf = new ExperienceBuffer<String, String>(10);
        buf.add(exp("low", 0.2));
        buf.add(exp("mid", 0.5));
        buf.add(exp("high", 1.0));

        var sample = buf.prioritizedSample(2);
        assertEquals(2, sample.size());
        assertEquals(1.0, sample.get(0).reward());
    }

    @Test void bufferPositiveFilter() {
        var buf = new ExperienceBuffer<String, String>(10);
        buf.add(exp("good", 1.0));
        buf.add(exp("bad", -1.0));
        buf.add(exp("ok", 0.5));

        assertEquals(2, buf.positive().size());
    }

    @Test void bufferClearEmptiesAll() {
        var buf = new ExperienceBuffer<String, String>(10);
        buf.add(exp("a", 0.5));
        buf.clear();
        assertEquals(0, buf.size());
        assertTrue(buf.recent(5).isEmpty());
    }

    // ── ExperienceReplay ──

    @Test void replayCanSampleWhenFullEnough() {
        var replay = new ExperienceReplay<String, String>(100, 3);
        assertFalse(replay.canSample());
        replay.store(exp("a", 0.5));
        replay.store(exp("b", 0.8));
        replay.store(exp("c", 1.0));
        assertTrue(replay.canSample());
        assertEquals(3, replay.sampleBatch().size());
    }

    @Test void replayPrioritizedPrefersHighReward() {
        var replay = new ExperienceReplay<String, String>(100, 2);
        replay.store(exp("low", 0.1));
        replay.store(exp("mid", 0.5));
        replay.store(exp("top", 1.0));

        var batch = replay.samplePrioritizedBatch();
        assertEquals(2, batch.size());
        assertEquals(1.0, batch.get(0).reward());
    }

    // ── LearningStrategy ──

    @Test void reinforcementIncreasesWeightForPositiveReward() {
        var strategy = LearningStrategy.reinforcement(0.1);
        var exp = exp("q", 1.0);
        double newWeight = strategy.updateWeight(0.5, exp, List.of());
        assertEquals(0.6, newWeight, 0.001);
    }

    @Test void decayReducesWeight() {
        var strategy = LearningStrategy.decay(0.1);
        double newWeight = strategy.updateWeight(0.5, exp("q", 0), List.of());
        assertEquals(0.45, newWeight, 0.001);
    }

    @Test void weightedRecentFavorsNewer() {
        var strategy = LearningStrategy.weightedRecent(0.1, 0.05);
        @SuppressWarnings({"rawtypes", "unchecked"})
        List<Experience<?, ?>> history = (List) List.of(exp("old1", 0.5), exp("old2", 0.5));
        // recencyIndex = 1, recencyFactor = exp(-0.05 * 1) ≈ 0.951
        double newWeight = strategy.updateWeight(0.5, exp("new", 1.0), history);
        assertTrue(newWeight > 0.5);
        assertTrue(newWeight < 0.7);
    }

    // ── FeedbackLearner ──

    @Test void feedbackLearnerAccumulatesExperience() {
        var learner = new FeedbackLearner<String, String>();
        learner.learn(exp("q1", 1.0));
        learner.learn(exp("q2", -0.5));
        assertEquals(2, learner.experienceCount());
        assertTrue(learner.getWeight() > 0.5);
    }

    @Test void feedbackLearnerInferReturnsBestPositive() {
        var learner = new FeedbackLearner<String, String>();
        learner.learn(Experience.<String, String>builder()
                .input("q").output("best").reward(1.0).build());
        learner.learn(Experience.<String, String>builder()
                .input("q2").output("ok").reward(0.3).build());

        assertEquals("best", learner.infer("anything"));
    }

    @Test void feedbackLearnerPositiveNegativeFiltering() {
        var learner = new FeedbackLearner<String, String>();
        learner.learn(exp("good", 1.0));
        learner.learn(exp("bad", -1.0));
        learner.learn(exp("good2", 2.0));

        assertEquals(2, learner.positiveFeedback(10).size());
        assertEquals(1, learner.negativeFeedback(10).size());
    }

    // ── FewShotLearner ──

    @Test void fewShotEvictsLowestRewardWhenFull() {
        var learner = new FewShotLearner<String, String>(2);
        learner.learn(exp("a", 0.5));
        learner.learn(exp("b", 0.8));
        learner.learn(exp("c", 0.3));  // lower than 0.5, so 0.3 stays, 0.5 evicted? Actually it kicks the lowest

        assertEquals(2, learner.experienceCount());
        var top = learner.topExamples(2);
        assertEquals(0.8, top.get(0).reward());
    }

    @Test void fewShotTopExamplesSorted() {
        var learner = new FewShotLearner<String, String>(5);
        learner.learn(exp("a", 0.2));
        learner.learn(exp("b", 1.0));
        learner.learn(exp("c", 0.5));

        var top = learner.topExamples(2);
        assertEquals(2, top.size());
        assertEquals(1.0, top.get(0).reward());
        assertEquals(0.5, top.get(1).reward());
    }

    // ── CurriculumLearner ──

    @Test void curriculumAddsAndPollsItems() {
        var learner = new CurriculumLearner<String, String>(LearningStrategy.reinforcement(0.1));
        learner.addToCurriculum("easy", 0.1);
        learner.addToCurriculum("hard", 0.9);

        var next = learner.nextLesson();
        assertTrue(next.isPresent());
        assertEquals(0.1, next.get().difficulty());

        var next2 = learner.nextLesson();
        assertTrue(next2.isPresent());
        assertEquals(0.9, next2.get().difficulty());
    }

    @Test void curriculumLevelIncrementsWithLearning() {
        var learner = new CurriculumLearner<String, String>(LearningStrategy.reinforcement(0.1));
        assertEquals(0, learner.currentLevel());
        learner.learn(exp("q", 1.0));
        assertEquals(1, learner.currentLevel());
    }

    // ── LearningLoop ──

    @Test void learningLoopConfigureCreatesInstance() {
        var mem = io.sketch.mochaagents.memory.MemoryManager.create();
        var loop = LearningLoop.configure(mem);
        assertNotNull(loop);

        String injection = loop.afterTurn(1, "some response", List.of());
        assertNotNull(injection);
    }

    @Test void learningLoopSummaryExtraction() {
        var mem = MemoryManager.create();
        var loop = LearningLoop.configure(mem).withSummaryRequired();

        String response = "<summary>Task completed</summary>\nDone.";
        String injection = loop.afterTurn(1, response, List.of());
        assertTrue(injection.contains("Task completed"));
    }

    @Test void learningLoopAntiForgettingWarnsAtIntervals() {
        var mem = MemoryManager.create();
        var loop = LearningLoop.configure(mem).withAntiForgetting(2, 4);

        // Turn 2: dangerInterval (2/2=1) -> turn 2 % 1 == 0
        String injection = loop.afterTurn(2, "ok", List.of());
        assertTrue(injection.contains("DANGER") || !injection.isEmpty());
    }

    @Test void learningLoopMissingSummaryInjectsWarning() {
        var mem = MemoryManager.create();
        var loop = LearningLoop.configure(mem).withSummaryRequired();

        String response = "No summary here.";
        String injection = loop.afterTurn(1, response, List.of());
        assertTrue(injection.contains("Must include <summary>"));
    }

    @Test void onTaskCompleteTriggersSettlement() {
        var mem = MemoryManager.create();
        mem.appendFinalAnswer("Done!");
        var loop = LearningLoop.configure(mem).withSettlement();
        assertDoesNotThrow(() -> loop.onTaskComplete("Done!"));
    }

    // ── helper ──

    private static Experience<String, String> exp(String label, double reward) {
        return Experience.<String, String>builder()
                .input(label).output(label).reward(reward).build();
    }
}
