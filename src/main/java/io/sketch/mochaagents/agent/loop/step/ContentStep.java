package io.sketch.mochaagents.agent.loop.step;

import java.util.List;

/**
 * 内容步 — 统一的轻量级步骤，合并 SystemPromptStep / TaskStep / FinalAnswerStep.
 *
 * <p>三种语义通过工厂方法区分，内部共享同一 record 结构：
 * <ul>
 *   <li>{@link #systemPrompt(String)} — 系统提示步 (type="system_prompt")</li>
 *   <li>{@link #task(String)} — 任务步 (type="task")</li>
 *   <li>{@link #finalAnswer(Object)} — 最终答案步 (type="final_answer")</li>
 * </ul>
 *
 * @param type       步骤类型标识
 * @param text       文本内容（system_prompt / task）
 * @param payload    附加负载（final_answer 的 output）
 * @param imagePaths 关联图片路径（task 专用，默认空列表）
 */
public record ContentStep(String type, String text, Object payload, List<String> imagePaths)
        implements MemoryStep {

    // ============ 工厂方法 ============

    public static ContentStep systemPrompt(String prompt) {
        return new ContentStep("system_prompt", prompt, null, List.of());
    }

    public static ContentStep task(String task) {
        return new ContentStep("task", task, null, List.of());
    }

    public static ContentStep finalAnswer(Object output) {
        return new ContentStep("final_answer", null, output, List.of());
    }

    // ============ 类型判定 ============

    public boolean isSystemPrompt() { return "system_prompt".equals(type); }
    public boolean isTask()           { return "task".equals(type); }
    public boolean isFinalAnswer()    { return "final_answer".equals(type); }
}
