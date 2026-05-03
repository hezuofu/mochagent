package io.sketch.mochaagents.agent.loop.strategy;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.agent.MemoryProvider;
import io.sketch.mochaagents.agent.loop.*;
import io.sketch.mochaagents.memory.AgentMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Think-Act-Observe — 三步认知循环.
 *
 * <p>Think: 生成推理 → Act: 执行工具/代码 → Observe: 记录结果，迭代至终止.
 * <p>与 ReActLoop 共享相同的 StepExecutor 契约，可在 MultiStepAgent 中替换使用.
 * @author lanxia39@163.com
 */
public class ThinkActObserve<I, O> implements AgenticLoop<I, O> {

    private static final Logger log = LoggerFactory.getLogger(ThinkActObserve.class);

    @FunctionalInterface
    public interface StepExecutor<I> {
        StepResult execute(int stepNumber, I input, AgentMemory memory);
    }

    @FunctionalInterface
    public interface PlanningFn<I> {
        String plan(int stepNumber, I input, AgentMemory memory);
    }

    private final PlanningFn<I> planningFn;
    private final StepExecutor<I> stepExecutor;

    public ThinkActObserve(PlanningFn<I> planningFn, StepExecutor<I> stepExecutor) {
        this.planningFn = planningFn;
        this.stepExecutor = stepExecutor;
    }

    @Override
    public O run(Agent<I, O> agent, I input, TerminationCondition condition) {
        String agentName = agent.metadata().name();
        AgentMemory memory = getMemory(agent);
        log.info("[{}] TAO loop starting", agentName);

        int step = 1;
        StepResult result;
        do {
            // Think phase
            if (planningFn != null && memory != null) {
                String plan = planningFn.plan(step, input, memory);
                if (plan != null) {
                    memory.appendPlanning(plan, "", 0, 0);
                }
            }

            // Act + Observe phase (handled by executor)
            long stepStart = System.currentTimeMillis();
            result = stepExecutor.execute(step, input, memory);
            long stepMs = System.currentTimeMillis() - stepStart;

            log.info("[{}] step {}: action={}, state={}, duration={}ms",
                    agentName, step, result != null ? result.action() : "?",
                    result != null ? result.state() : "?", stepMs);
            step++;

        } while (!condition.shouldTerminate(result) && (memory == null || !memory.hasFinalAnswer()));

        @SuppressWarnings("unchecked")
        O output = result != null ? (O) result.output() : null;
        log.info("[{}] TAO loop finished: {} steps", agentName, step - 1);
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
