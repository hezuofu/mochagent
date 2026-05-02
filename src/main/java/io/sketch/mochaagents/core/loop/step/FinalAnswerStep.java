package io.sketch.mochaagents.core.loop.step;

/**
 * 最终答案步 — 记录 Agent 任务的最终输出.
 */
public record FinalAnswerStep(Object output) implements MemoryStep {

    @Override
    public String type() { return "final_answer"; }
}
