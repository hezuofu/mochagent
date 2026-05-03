package io.sketch.mochaagents.agent.loop.strategy;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.agent.MemoryProvider;
import io.sketch.mochaagents.agent.loop.*;
import io.sketch.mochaagents.agent.loop.ReflectionEngine;
import io.sketch.mochaagents.agent.loop.SelfCritique;
import io.sketch.mochaagents.memory.AgentMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Observe-Plan-Act-Reflect — 四阶段认知循环.
 *
 * <p>Observe: 检查当前状态 → Plan: 生成下一步计划 → Act: 执行 → Reflect: 评估并调整.
 * @author lanxia39@163.com
 */
public class ObservePlanActReflect<I, O> implements AgenticLoop<I, O> {

    private static final Logger log = LoggerFactory.getLogger(ObservePlanActReflect.class);

    @FunctionalInterface
    public interface Observer<I> {
        String observe(int stepNumber, I input, AgentMemory memory);
    }

    @FunctionalInterface
    public interface StepExecutor<I> {
        StepResult execute(int stepNumber, I input, AgentMemory memory);
    }

    @FunctionalInterface
    public interface PlanningFn<I> {
        String plan(int stepNumber, I input, AgentMemory memory);
    }

    private final Observer<I> observer;
    private final PlanningFn<I> planningFn;
    private final StepExecutor<I> stepExecutor;
    private final ReflectionEngine reflectionEngine;
    private final int reflectInterval;

    public ObservePlanActReflect(Observer<I> observer, PlanningFn<I> planningFn,
                                  StepExecutor<I> stepExecutor, ReflectionEngine reflectionEngine,
                                  int reflectInterval) {
        this.observer = observer;
        this.planningFn = planningFn;
        this.stepExecutor = stepExecutor;
        this.reflectionEngine = reflectionEngine != null ? reflectionEngine : ReflectionEngine.noop();
        this.reflectInterval = reflectInterval;
    }

    @Override
    public O run(Agent<I, O> agent, I input, TerminationCondition condition) {
        String agentName = agent.metadata().name();
        AgentMemory memory = getMemory(agent);
        log.info("[{}] OPAR loop starting", agentName);

        int step = 1;
        StepResult result = null;
        do {
            // 1. Observe
            if (observer != null && memory != null) {
                String obs = observer.observe(step, input, memory);
                if (obs != null && !obs.isEmpty()) {
                    memory.append(new io.sketch.mochaagents.agent.loop.step.ContentStep(
                            "observation", obs, null, java.util.List.of()));
                }
            }

            // 2. Plan
            if (planningFn != null && memory != null) {
                String planText = planningFn.plan(step, input, memory);
                if (planText != null) {
                    memory.appendPlanning(planText, "", 0, 0);
                }
            }

            // 3. Act
            long stepStart = System.currentTimeMillis();
            result = stepExecutor.execute(step, input, memory);
            long stepMs = System.currentTimeMillis() - stepStart;

            // 4. Reflect (periodically)
            if (memory != null && step % reflectInterval == 0 && result != null) {
                SelfCritique critique = SelfCritique.builder()
                        .analysis(result.observation() != null ? result.observation() : "")
                        .needsImprovement(result.hasError())
                        .suggestion(result.error() != null ? result.error() : "continue")
                        .build();
                var improvement = reflectionEngine.reflect(result, critique);
                if (improvement != null && !improvement.summary().isEmpty()) {
                    log.debug("[{}] Reflect step {}: {}", agentName, step, improvement.summary());
                }
            }

            log.info("[{}] step {}: action={}, state={}, duration={}ms",
                    agentName, step, result != null ? result.action() : "?",
                    result != null ? result.state() : "?", stepMs);
            step++;

        } while (!condition.shouldTerminate(result) && (memory == null || !memory.hasFinalAnswer()));

        @SuppressWarnings("unchecked")
        O output = result != null ? (O) result.output() : null;
        log.info("[{}] OPAR loop finished: {} steps", agentName, step - 1);
        return output;
    }

    @Override
    public StepResult step(Agent<I, O> agent, I input, int stepNum) {
        AgentMemory memory = getMemory(agent);
        return stepExecutor.execute(stepNum, input, memory);
    }

    private static AgentMemory getMemory(Agent<?, ?> agent) {
        if (agent instanceof MemoryProvider mp) return mp.memory();
        return null;
    }
}
