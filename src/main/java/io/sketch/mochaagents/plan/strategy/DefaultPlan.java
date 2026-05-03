package io.sketch.mochaagents.plan.strategy;

import io.sketch.mochaagents.plan.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 默认计划实现 — Plan 接口的基础实现.
 * @author lanxia39@163.com
 */
class DefaultPlan<T> implements Plan<T> {

    private final String planId = UUID.randomUUID().toString();
    private final T goal;
    private final List<PlanStep> steps = new ArrayList<>();
    private int currentStep = 0;
    private PlanStep.PlanStatus status = PlanStep.PlanStatus.DRAFT;
    private final DependencyGraph graph = new DependencyGraph();

    DefaultPlan(PlanningRequest<T> request) {
        this.goal = request.goal();
    }

    @Override public String getPlanId() { return planId; }
    @Override public T getGoal() { return goal; }
    @Override public List<PlanStep> getSteps() { return List.copyOf(steps); }
    @Override public PlanStep getCurrentStep() { return currentStep < steps.size() ? steps.get(currentStep) : null; }
    @Override public void advance() { currentStep++; }
    @Override public PlanStep.PlanStatus getStatus() { return status; }
    @Override public void setStatus(PlanStep.PlanStatus status) { this.status = status; }
    @Override public DependencyGraph getDependencyGraph() { return graph; }
    @Override public long estimateExecutionTime() { return steps.size() * 1000L; }
    @Override public ValidationResult validate() { return graph.hasCycle() ? ValidationResult.invalid("Cycle detected") : ValidationResult.valid(); }
    @Override public String serialize() { return "Plan[id=" + planId + ", steps=" + steps.size() + "]"; }

    public void addStep(PlanStep step) { steps.add(step); }
}
