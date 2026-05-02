package io.sketch.mochaagents.agent.loop.step;

/**
 * 系统提示步 — 记录 Agent 启动时的 System Prompt.
 */
public record SystemPromptStep(String systemPrompt) implements MemoryStep {

    @Override
    public String type() { return "system_prompt"; }
}
