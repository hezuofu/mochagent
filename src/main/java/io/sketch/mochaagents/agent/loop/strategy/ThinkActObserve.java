package io.sketch.mochaagents.agent.loop.strategy;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.agent.loop.AgenticLoop;
import io.sketch.mochaagents.agent.loop.LoopState;
import io.sketch.mochaagents.agent.loop.StepResult;
import io.sketch.mochaagents.agent.loop.TerminationCondition;

/**
 * Think-Act-Observe 策略 — 先思考，再行动，最后观察.
 *
 * <p>三步循环，更强调前置推理阶段.
 */
public class ThinkActObserve<I, O> implements AgenticLoop<I, O> {

    @Override
    public O run(Agent<I, O> agent, I input, TerminationCondition condition) {
        O lastOutput = null;
        int stepNum = 0;
        while (true) {
            StepResult result = step(agent, input, stepNum + 1);
            stepNum++;
            if (condition.shouldTerminate(result)) {
                break;
            }
            lastOutput = agent.execute(input);
        }
        return lastOutput;
    }

    @Override
    public StepResult step(Agent<I, O> agent, I input, int stepNum) {
        long start = System.currentTimeMillis();
        try {
            // Think phase
            StepResult thinkResult = StepResult.builder()
                    .stepNumber(stepNum)
                    .state(LoopState.PLAN)
                    .action("think")
                    .durationMs(0)
                    .build();

            // Act phase
            O output = agent.execute(input);

            // Observe phase
            return StepResult.builder()
                    .stepNumber(stepNum)
                    .state(LoopState.OBSERVE)
                    .observation(output != null ? output.toString() : "")
                    .action("think→act→observe")
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
