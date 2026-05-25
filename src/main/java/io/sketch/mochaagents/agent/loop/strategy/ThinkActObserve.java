// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent.loop.strategy;
import io.sketch.mochaagents.agent.loop.Termination;

import io.sketch.mochaagents.agent.Agent;
import java.util.function.Predicate;
import io.sketch.mochaagents.memory.MemoryProvider;
import io.sketch.mochaagents.agent.AgentLoop;
import io.sketch.mochaagents.agent.loop.*;
import io.sketch.mochaagents.memory.MemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Think-Act-Observe — 三步认知循环.
 *
 * <p>Think: 生成推理 → Act: 执行工具/代码 → Observe: 记录结果，迭代至终止.
 * <p>与 ReActLoop 共享相同的 StepExecutor 契约，可在 ReActAgent 中替换使用.
 * @author lanxia39@163.com
 */
public class ThinkActObserve<I, O> implements AgentLoop<I, O> {

    private static final Logger log = LoggerFactory.getLogger(ThinkActObserve.class);

    @FunctionalInterface
    public interface StepExecutor<I> {
        StepResult execute(int stepNumber, I input, MemoryManager memory);
    }

    @FunctionalInterface
    public interface PlanningFn<I> {
        String plan(int stepNumber, I input, MemoryManager memory);
    }

    private final PlanningFn<I> planningFn;
    private final StepExecutor<I> stepExecutor;

    public ThinkActObserve(PlanningFn<I> planningFn, StepExecutor<I> stepExecutor) {
        this.planningFn = planningFn;
        this.stepExecutor = stepExecutor;
    }

    @Override
    public O run(Agent<I, O> agent, I input, Predicate<StepResult> condition) {
        String agentName = agent.metadata().name();
        MemoryManager memory = MemoryProvider.of(agent);
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

        } while (!condition.test(result) && Termination.notDone(memory));

        @SuppressWarnings("unchecked")
        O output = result != null ? (O) result.output() : null;
        log.info("[{}] TAO loop finished: {} steps", agentName, step - 1);
        return output;
    }


}
