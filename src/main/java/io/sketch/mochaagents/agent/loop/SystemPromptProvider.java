package io.sketch.mochaagents.agent.loop;

/**
 * Contract for agents that generate their own system prompt.
 * @author lanxia39@163.com
 */
public interface SystemPromptProvider {
    String buildSystemPrompt();
}
