package io.sketch.mochaagents.plan.decomposer;

import io.sketch.mochaagents.plan.PlanStep;
import java.util.List;

/**
 * 任务分解器接口.
 */
public interface TaskDecomposer {
    List<PlanStep> decompose(String task, int maxSteps);
}
