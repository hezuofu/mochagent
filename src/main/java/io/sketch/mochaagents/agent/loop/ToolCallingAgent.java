// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent.loop;

import io.sketch.mochaagents.agent.loop.LoopState;
import io.sketch.mochaagents.memory.MemoryManager;
import io.sketch.mochaagents.agent.loop.ReActAgent;
import io.sketch.mochaagents.agent.loop.StepResult;
import io.sketch.mochaagents.model.ModelRequest;
import io.sketch.mochaagents.model.ModelResponse;
import io.sketch.mochaagents.agent.loop.step.ActionStep;
import io.sketch.mochaagents.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * ToolCallingAgent — Model generates text, framework parses and executes tools.
 *
 * <p>Tool call parsing (parseAction/parseKvArgs/parseJsonArgs) is inherited
 * from {@link io.sketch.mochaagents.agent.internal.BaseAgent}.
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

                ## Response Format
                Thought: <your reasoning about what to do next>
                Action: <tool_name>(arguments)
                Use key=\"value\" for arguments. End tasks with:
                Action: final_answer(answer=\"your answer\")

                ## Rules
                - ALWAYS call a tool to take action — never just describe intentions.
                - NEVER fabricate tool results — wait for the Observation.
                - If a tool fails, try a different approach.
                - If a tool returns empty results, retry with different parameters.
                - When info is missing, use a tool to look it up — don't guess.

                ## Verification
                Before final_answer, verify:
                - Did the output satisfy every requirement?
                - Are factual claims backed by tool outputs?
                - If the next step has side effects, confirm scope first.

                You will receive an Observation after each action.
                """, formatTools());
    }

    @Override
    protected StepResult executeReActStep(int stepNumber, String input, MemoryManager memory) {
        long start = System.currentTimeMillis();

        try {
            List<Map<String, String>> messages = writeMemoryToMessages();

            ModelRequest request = ModelRequest.builder()
                    .messages(messages).maxTokens(2048).temperature(0.7)
                    .thinkingConfig(thinkingConfig).effort(effortLevel).build();

            ModelResponse response = withLlmRetry(() -> model.complete(request), "step " + stepNumber);
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
