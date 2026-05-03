package io.sketch.mochaagents.memory;

import io.sketch.mochaagents.agent.loop.step.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent 记忆 — 所有 Agent 的通用执行轨迹记录器.
 *
 * <p>记录完整的思考-行动-观察历史，支持：
 * <ul>
 *   <li>追加各类步骤（System/Planning/Action/FinalAnswer）</li>
 *   <li>重置会话上下文</li>
 *   <li>导出为 LLM 消息格式（用于上下文续写）</li>
 *   <li>snapshot/restore — 与 {@link MemoryManager} 持久化桥接</li>
 * </ul>
 *
 * <p>对应 smolagents 的 {@code AgentMemory}.
 * @author lanxia39@163.com
 */
public class AgentMemory {

    private volatile String systemPrompt;
    private final List<MemoryStep> steps = new CopyOnWriteArrayList<>();

    /** 设置系统提示（内部使用，不追加为独立步骤）. */
    public void setSystemPrompt(String prompt) {
        this.systemPrompt = prompt;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    /** 追加一个步骤. */
    public void append(MemoryStep step) {
        steps.add(step);
    }

    /** 获取所有步骤（不可变视图）. */
    public List<MemoryStep> steps() {
        return Collections.unmodifiableList(steps);
    }

    /** 清空所有步骤（保留 system prompt）. */
    public void reset() {
        steps.clear();
    }

    /** 清空所有步骤并重置 system prompt. */
    public void reset(String newSystemPrompt) {
        steps.clear();
        this.systemPrompt = newSystemPrompt;
    }

    /** 步数. */
    public int size() {
        return steps.size();
    }

    /** 获取最后一步（函数式安全 — Optional 替代 null）. */
    public java.util.Optional<MemoryStep> lastStep() {
        return steps.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of(steps.get(steps.size() - 1));
    }

    /** 是否以最终答案步结束. */
    public boolean hasFinalAnswer() {
        return lastStep().filter(s -> s instanceof ContentStep cs && cs.isFinalAnswer()).isPresent();
    }

    // ============ 便捷追加方法 ============

    public void appendSystemPrompt(String prompt) {
        steps.add(ContentStep.systemPrompt(prompt));
    }

    public void appendTask(String task) {
        steps.add(ContentStep.task(task));
    }

    public void appendPlanning(String plan, String modelOutput, int inputTokens, int outputTokens) {
        steps.add(new PlanningStep(plan, modelOutput, inputTokens, outputTokens));
    }

    public void appendAction(ActionStep step) {
        steps.add(step);
    }

    public void appendFinalAnswer(Object output) {
        steps.add(ContentStep.finalAnswer(output));
    }

    // ============ 持久化桥接 ============

    /**
     * 将当前执行轨迹转为 Memory 列表，供 MemoryManager 持久化.
     * 每个 ActionStep 生成一条情景记忆；FinalAnswerStep 生成一条语义记忆.
     */
    public List<Memory> snapshot() {
        List<Memory> entries = new ArrayList<>();
        for (MemoryStep s : steps) {
            if (s instanceof ActionStep act) {
                String content = "[Step " + act.stepNumber() + "] "
                        + (act.observation() != null ? act.observation() : act.modelOutput());
                entries.add(MemoryEntry.builder()
                        .content(content).type(Memory.TYPE_EPISODIC)
                        .importance(act.hasError() ? 0.2 : 0.6).build());
            } else if (s instanceof ContentStep cs && cs.isFinalAnswer()) {
                entries.add(MemoryEntry.builder()
                        .content("Task result: " + cs.payload()).type(Memory.TYPE_SEMANTIC)
                        .concepts(Set.of("final", "output")).build());
            }
        }
        return entries;
    }

    /** 从 MemoryManager 恢复历史记忆到当前 AgentMemory（追加为 ActionStep）. */
    public void restore(List<Memory> entries) {
        for (Memory e : entries) {
            String content = e.content();
            ActionStep step = ActionStep.empty(steps.size() + 1);
            steps.add(new ActionStep(
                    step.stepNumber(), "", "",
                    "restored", content, null, 0, 0, false));
        }
    }
}
