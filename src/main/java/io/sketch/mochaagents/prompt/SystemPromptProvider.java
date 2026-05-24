package io.sketch.mochaagents.prompt;

/**
 * Contract for agents that generate their own system prompt.
 * @author lanxia39@163.com
 */
import io.sketch.mochaagents.agent.Agent;

public interface SystemPromptProvider {
    String buildSystemPrompt();

    static String of(Agent<?, ?> agent) {
        return agent instanceof SystemPromptProvider spp ? spp.buildSystemPrompt() : "";
    }
}
