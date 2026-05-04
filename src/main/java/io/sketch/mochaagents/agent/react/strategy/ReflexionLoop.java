package io.sketch.mochaagents.agent.react.strategy;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.agent.MemoryProvider;
import io.sketch.mochaagents.agent.react.*;
import io.sketch.mochaagents.memory.AgentMemory;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reflexion loop — self-critique + improvement after every turn.
 *
 * <pre>
 *   ACT → OBSERVE → SELF-CRITIQUE → IMPROVEMENT → (loop with better strategy)
 * </pre>
 *
 * <p>Unlike OPAR's periodic reflection, Reflexion critiques EVERY step,
 * produces a concrete improvement plan, and passes it to the next cycle.
 * The improvement is injected as context so the LLM learns from its mistakes.
 *
 * <p>Reference: Shinn et al. "Reflexion: Language Agents with Verbal Reinforcement Learning"
 *
 * @author lanxia39@163.com
 */
public class ReflexionLoop<I, O> implements AgenticLoop<I, O> {

    private static final Logger log = LoggerFactory.getLogger(ReflexionLoop.class);

    @FunctionalInterface
    public interface StepExecutor<I> {
        StepResult execute(int stepNumber, I input, AgentMemory memory);
    }

    @FunctionalInterface
    public interface Critic {
        SelfCritique critique(int stepNumber, StepResult result, AgentMemory memory);
    }

    private final StepExecutor<I> stepExecutor;
    private final ReflectionEngine reflectionEngine;
    private final Critic critic;
    private final int maxImprovements;

    public ReflexionLoop(StepExecutor<I> stepExecutor, ReflectionEngine reflectionEngine) {
        this(stepExecutor, reflectionEngine, null, 10);
    }

    public ReflexionLoop(StepExecutor<I> stepExecutor, ReflectionEngine reflectionEngine,
                          Critic critic, int maxImprovements) {
        this.stepExecutor = stepExecutor;
        this.reflectionEngine = reflectionEngine != null ? reflectionEngine : ReflectionEngine.noop();
        this.critic = critic != null ? critic : ReflexionLoop::defaultCritique;
        this.maxImprovements = maxImprovements;
    }

    @Override
    public O run(Agent<I, O> agent, I input, Predicate<StepResult> condition) {
        String agentName = agent.metadata().name();
        AgentMemory memory = getMemory(agent);
        log.info("[{}] Reflexion loop starting (max improvements: {})", agentName, maxImprovements);

        int step = 1;
        int improvementCount = 0;
        StepResult result = null;
        SelfCritique lastCritique = null;

        do {
            // 1. Inject any improvement from previous reflection into memory
            if (lastCritique != null && lastCritique.needsImprovement() && memory != null) {
                var improvement = reflectionEngine.reflect(result, lastCritique);
                if (improvement != null && !improvement.summary().isEmpty()) {
                    String adjustments = improvement.adjustments() != null
                            && !improvement.adjustments().isEmpty()
                            ? String.join("; ", improvement.adjustments())
                            : "review and improve";
                    String ctx = "[Reflexion Improvement #" + improvementCount + "]:\n"
                            + improvement.summary() + "\n"
                            + "Adjustments: " + adjustments;
                    memory.append(new io.sketch.mochaagents.agent.react.step.ContentStep(
                            "reflection", ctx, null, java.util.List.of()));
                    log.info("[{}] improvement #{} injected: {}", agentName,
                            improvementCount, improvement.summary());
                }
            }

            // 2. Act — LLM call + tool execution
            long stepStart = System.currentTimeMillis();
            result = stepExecutor.execute(step, input, memory);
            long stepMs = System.currentTimeMillis() - stepStart;

            // 3. Self-critique — analyze what went right/wrong
            lastCritique = critic.critique(step, result, memory);

            if (lastCritique.needsImprovement() && improvementCount < maxImprovements) {
                improvementCount++;
            }

            log.info("[{}] step {}: action={}, state={}, needsImprovement={}, duration={}ms",
                    agentName, step,
                    result != null ? result.action() : "?",
                    result != null ? result.state() : "?",
                    lastCritique.needsImprovement(),
                    stepMs);
            step++;

        } while (!condition.test(result) && (memory == null || !memory.hasFinalAnswer()));

        @SuppressWarnings("unchecked")
        O output = result != null ? (O) result.output() : null;
        log.info("[{}] Reflexion loop finished: {} steps, {} improvements",
                agentName, step - 1, improvementCount);
        return output;
    }

    @Override
    public StepResult step(Agent<I, O> agent, I input, int stepNum) {
        AgentMemory memory = getMemory(agent);
        return stepExecutor.execute(stepNum, input, memory);
    }

    /** Default critique: detect errors and low-quality output. */
    private static SelfCritique defaultCritique(int step, StepResult result, AgentMemory memory) {
        boolean hasError = result != null && result.hasError();
        String analysis = result != null && result.observation() != null
                ? result.observation() : "no observation";
        return SelfCritique.builder()
                .analysis(analysis)
                .needsImprovement(hasError)
                .suggestion(hasError ? "Fix the error and retry" : "continue")
                .build();
    }

    private static AgentMemory getMemory(Agent<?, ?> agent) {
        if (agent instanceof MemoryProvider mp) return mp.memory();
        return null;
    }
}
