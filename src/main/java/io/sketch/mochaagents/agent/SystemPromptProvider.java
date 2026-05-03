package io.sketch.mochaagents.agent;

/**
 * Contract for agents that generate their own system prompt.
 * @author lanxia39@163.com
 */
public interface SystemPromptProvider {
    String buildSystemPrompt();
}
