package io.sketch.mochaagents.agent.loop.strategy;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.agent.loop.AgenticLoop;
import io.sketch.mochaagents.agent.loop.LoopState;
import io.sketch.mochaagents.agent.loop.StepResult;
import io.sketch.mochaagents.agent.loop.TerminationCondition;

/**
 * Observe-Plan-Act-Reflect 策略 — 四阶段认知循环.
 *
 * <p>观察 → 规划 → 执行 → 反思，每步后评估是否继续.
 * @author lanxia39@163.com
 */
public class ObservePlanActReflect<I, O> implements AgenticLoop<I, O> {

    @Override
    public O run(Agent<I, O> agent, I input, TerminationCondition condition) {
        O currentOutput = null;
        int stepNum = 0;
        while (!condition.shouldTerminate(StepResult.builder().stepNumber(stepNum).state(LoopState.COMPLETE).build())) {
            long start = System.currentTimeMillis();
            StepResult result = step(agent, input, stepNum + 1);
            stepNum++;
            if (result.hasError() || result.state() == LoopState.COMPLETE) {
                currentOutput = agent.execute(input);
                break;
            }
        }
        return currentOutput != null ? currentOutput : agent.execute(input);
    }

    @Override
    public StepResult step(Agent<I, O> agent, I input, int stepNum) {
        long start = System.currentTimeMillis();
        try {
            O output = agent.execute(input);
            return StepResult.builder()
                    .stepNumber(stepNum)
                    .state(LoopState.ACT)
                    .action("execute")
                    .output(output != null ? output.toString() : "")
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        } catch (Exception e) {
            return StepResult.builder()
                    .stepNumber(stepNum)
                    .state(LoopState.ERROR)
                    .error(e.getMessage())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }
    }
}
