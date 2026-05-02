package io.sketch.mochaagents.agent.loop.strategy;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.agent.loop.AgenticLoop;
import io.sketch.mochaagents.agent.loop.LoopState;
import io.sketch.mochaagents.agent.loop.StepResult;
import io.sketch.mochaagents.agent.loop.TerminationCondition;

/**
 * ReAct 模式 — Reasoning + Acting 交替执行.
 *
 * <p>每一步先推理(reason)再行动(act)，观察结果后决定下一步.
 */
public class ReActLoop<I, O> implements AgenticLoop<I, O> {

    private final int maxSteps;

    public ReActLoop(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    @Override
    public O run(Agent<I, O> agent, I input, TerminationCondition condition) {
        O lastOutput = null;
        for (int i = 1; i <= maxSteps; i++) {
            StepResult result = step(agent, input, i);
            if (condition.shouldTerminate(result)) {
                break;
            }
            if (result.output() != null) {
                lastOutput = agent.execute(input);
            }
        }
        return lastOutput;
    }

    @Override
    public StepResult step(Agent<I, O> agent, I input, int stepNum) {
        long start = System.currentTimeMillis();
        try {
            O output = agent.execute(input);
            return StepResult.builder()
                    .stepNumber(stepNum)
                    .state(LoopState.ACT)
                    .observation("step " + stepNum)
                    .action("reason+act")
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
