package io.sketch.mochaagents.agent.loop;

import io.sketch.mochaagents.agent.loop.LoopState;
import io.sketch.mochaagents.agent.loop.ReActAgent;
import io.sketch.mochaagents.agent.loop.StepResult;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import io.sketch.mochaagents.memory.AgentMemory;
import io.sketch.mochaagents.agent.loop.step.ActionStep;
import io.sketch.mochaagents.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * ToolCallingAgent — LLM generates text, framework parses and executes tools.
 *
 * <p>Tool call parsing (parseAction/parseKvArgs/parseJsonArgs) is inherited
 * from {@link io.sketch.mochaagents.agent.impl.BaseAgent}.
 */
public final class ToolCallingAgent extends ReActAgent {

    private static final Logger log = LoggerFactory.getLogger(ToolCallingAgent.class);

    private ToolCallingAgent(Builder builder) { super(builder); }

    @Override
    public String buildSystemPrompt() {
        if (systemPromptTemplate != null && !systemPromptTemplate.template().isEmpty()) {
            return systemPromptTemplate.render(Map.of(
                    "tools", formatTools(),
                    "managed_agents", formatManagedAgents(),
                    "instructions", description != null ? description : ""
            ));
        }
        return String.format("""
                You are an AI assistant that solves tasks step by step.
                Available tools:
                %s

                ## Response Format (MANDATORY)
                Every response MUST contain exactly one action in this format:
                Thought: <your reasoning about what to do next>
                Action: <tool_name>(arguments)

                Arguments use key=\"value\" format: tool(key=\"value\")
                When the task is complete: Action: final_answer(answer=\"your answer\")

                ## Rules
                - NEVER describe what you plan to do — ACTUALLY call the tool.
                - NEVER fabricate tool results — wait for the Observation.
                - If a tool returns an error, try a different approach.
                - You MUST produce an Action: line in every response.

                You will receive an Observation after each action.
                """, formatTools());
    }

    @Override
    protected StepResult executeReActStep(int stepNumber, String input, AgentMemory memory) {
        long start = System.currentTimeMillis();

        try {
            List<Map<String, String>> messages = writeMemoryToMessages();

            LLMRequest request = LLMRequest.builder()
                    .messages(messages).maxTokens(2048).temperature(0.7)
                    .thinkingConfig(thinkingConfig).effort(effortLevel).build();

            LLMResponse response = withLlmRetry(() -> llm.complete(request), "step " + stepNumber);
            String modelOutput = response.content();
            log.info("[ToolCallingAgent] step {} LLM: {}ms, tokens in={} out={}",
                    stepNumber, System.currentTimeMillis() - start,
                    response.promptTokens(), response.completionTokens());

            ParsedAction action = parseAction(modelOutput);

            String observation;
            boolean isFinalAnswer = false;
            Object toolResult = null;

            if (action != null && toolRegistry != null && toolRegistry.has(action.name())) {
                try {
                    var result = executeTool(action.name(), action.arguments());
                    toolResult = result.output();
                    observation = result.isError()
                            ? "Tool error: " + result.error()
                            : String.valueOf(result.output());
                    isFinalAnswer = "final_answer".equals(action.name());
                } catch (Exception e) {
                    observation = "Tool error: " + e.getMessage();
                }
            } else if (action != null) {
                observation = "Tool not found: " + action.name()
                        + ". Available: " + (toolRegistry != null
                        ? toolRegistry.all().stream().map(Tool::getName).toList() : "none");
            } else {
                observation = "Could not parse action. "
                        + "Use format: Action: tool_name(arguments)";
            }

            ActionStep actionStep = new ActionStep(
                    stepNumber, messages.toString(), modelOutput,
                    action != null ? action.name() + "(" + action.arguments() + ")" : "parse_error",
                    observation, null,
                    response.promptTokens(), response.completionTokens(), isFinalAnswer);
            memory.appendAction(actionStep);

            if (isFinalAnswer) {
                memory.appendFinalAnswer(toolResult);
                log.info("[ToolCallingAgent] step {} final_answer: {}",
                        stepNumber, truncate(String.valueOf(toolResult), 200));
            }

            return StepResult.builder()
                    .stepNumber(stepNumber)
                    .state(isFinalAnswer ? LoopState.COMPLETE : LoopState.ACT)
                    .action(action != null ? action.name() : "parse_error")
                    .observation(observation)
                    .output(isFinalAnswer ? String.valueOf(toolResult) : observation)
                    .durationMs(System.currentTimeMillis() - start)
                    .build();

        } catch (Exception e) {
            log.error("ToolCallingAgent step {} failed", stepNumber, e);
            memory.appendAction(new ActionStep(stepNumber, "", "", "", "",
                    e.getMessage(), 0, 0, false));
            return StepResult.builder()
                    .stepNumber(stepNumber).state(LoopState.ERROR)
                    .error(e.getMessage()).durationMs(System.currentTimeMillis() - start).build();
        }
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder extends ReActAgent.Builder<Builder> {
        @Override public ToolCallingAgent build() { return new ToolCallingAgent(this); }
    }
}
