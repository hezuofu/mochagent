package io.sketch.mochaagents.agent.react;

import java.util.List;

/**
 * 改进计划 — 基于自我批评生成的行动修正.
 * @author lanxia39@163.com
 */
public final class ImprovementPlan {

    private final String summary;
    private final List<String> adjustments;
    private final int priority;

    public ImprovementPlan(String summary, List<String> adjustments, int priority) {
        this.summary = summary;
        this.adjustments = List.copyOf(adjustments);
        this.priority = priority;
    }

    public String summary() { return summary; }
    public List<String> adjustments() { return adjustments; }
    public int priority() { return priority; }

    /**
     * 创建空改进计划.
     */
    public static ImprovementPlan empty() {
        return new ImprovementPlan("no improvements needed", List.of(), 0);
    }
}
