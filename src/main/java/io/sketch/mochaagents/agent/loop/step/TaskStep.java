package io.sketch.mochaagents.agent.loop.step;

import java.util.List;

/**
 * 任务步 — 记录用户提交的任务信息.
 */
public record TaskStep(String task, List<String> imagePaths) implements MemoryStep {

    public TaskStep(String task) {
        this(task, List.of());
    }

    @Override
    public String type() { return "task"; }
}
