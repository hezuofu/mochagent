package io.sketch.mochaagents.prompt;

/**
 * Contract for agents that generate their own system prompt.
 * @author lanxia39@163.com
 */
public interface SystemPromptProvider {
    String buildSystemPrompt();
}
