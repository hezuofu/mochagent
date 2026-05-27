// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent;

import io.sketch.mochaagents.agent.loop.LoopState;
import io.sketch.mochaagents.memory.MemoryManager;
import io.sketch.mochaagents.agent.loop.StepResult;
import io.sketch.mochaagents.message.ContentBlock;
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
  * @author lanxia39@163.com
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
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("""
                You are an AI assistant that solves tasks step by step.
                Available tools:
                %s

                ## Response Format
                Thought: <your reasoning about what to do next>
                Action: <tool_name>(arguments)
                Use key="value" for arguments. End tasks with:
                Action: final_answer(answer="your answer")

                ## Rules
                - ALWAYS call a tool to take action — never just describe intentions.
                - NEVER fabricate tool results — wait for the Observation.
                - If a tool fails, try a different approach.
                - If a tool returns empty results, retry with different parameters.
                - When info is missing, use a tool to look it up — don't guess.
                - Don't add features or abstractions beyond what the task requires.
                - Don't add error handling for scenarios that can't happen.
                - Default to no comments — only explain non-obvious WHY.

                ## Verification
                Before final_answer, verify:
                - Did the output satisfy every requirement?
                - Are factual claims backed by tool outputs?
                - If the next step has side effects, confirm scope first.

                You will receive an Observation after each action.
                """, formatTools()));

        // Model-specific guidance
        String family = model.modelFamily();
        if ("openai".equals(family)) {
            sb.append("\n## Execution Discipline\n");
            sb.append("- Use tools whenever they improve correctness, completeness, or grounding.\n");
            sb.append("- Do not stop early when another tool call would materially improve the result.\n");
            sb.append("- Never answer math, hashes, or current time from memory — use a tool.\n");
            sb.append("- Before acting, check if prerequisite discovery is needed.\n");
        } else if ("google".equals(family)) {
            sb.append("\n## Operational Directives\n");
            sb.append("- Use absolute paths for all file operations.\n");
            sb.append("- Verify file contents before making changes — never guess.\n");
            sb.append("- Keep explanations brief — a few sentences, not paragraphs.\n");
            sb.append("- Make parallel tool calls when operations are independent.\n");
            sb.append("- Use non-interactive flags (-y, --yes) to prevent CLI hangs.\n");
        }
        return sb.toString();
    }

    @Override
    protected StepResult executeReActStep(int stepNumber, String input, MemoryManager memory) {
        long start = System.currentTimeMillis();
        // Track step for file history (enables turn-level file rollback)
        io.sketch.mochaagents.tool.FileHistory.setCurrentStep(stepNumber);

        try {
            // Use typed messages for native ContentBlock-aware providers; fall back to flat maps
            List<io.sketch.mochaagents.message.Message> typedMsgs = writeTypedMessages();
            ModelRequest.Builder reqBuilder = ModelRequest.builder()
                    .typedMessages(typedMsgs).maxTokens(2048).temperature(0.7)
                    .thinkingConfig(thinkingConfig).effort(effortLevel);
            ModelRequest request = reqBuilder.build();

            ModelResponse response = withLlmRetry(() -> model.complete(request), "step " + stepNumber);
            String modelOutput = response.content();
            log.info("[ToolCallingAgent] step {} LLM: {}ms, tokens in={} out={}",
                    stepNumber, System.currentTimeMillis() - start,
                    response.promptTokens(), response.completionTokens());

            // ── Typed path: use contentBlocks when provider supports them ──
            List<ContentBlock> assistantBlocks = new ArrayList<>();
            List<ContentBlock.ToolResultBlock> toolResultBlocks = new ArrayList<>();
            String observation;
            boolean isFinalAnswer = false;
            Object toolResult = null;

            if (response.hasContentBlocks()) {
                // Extract typed blocks from response
                List<ParsedAction> actions = new ArrayList<>();
                for (var msg : response.contentBlocks()) {
                    if (msg instanceof io.sketch.mochaagents.message.Message.AssistantMessage am) {
                        for (var block : am.content()) {
                            assistantBlocks.add(block);
                            if (block instanceof io.sketch.mochaagents.message.ContentBlock.ToolUseBlock tb) {
                                actions.add(new ParsedAction(tb.name(), tb.input()));
                            }
                        }
                    }
                }

                if (!actions.isEmpty()) {
                    // Execute tools — concurrent for multiple, single for one
                    List<io.sketch.mochaagents.tool.ToolResult> results;
                    if (actions.size() == 1) {
                        ParsedAction a = actions.get(0);
                        results = List.of(executeTool(a.name(), a.arguments()));
                    } else {
                        var batcher = new io.sketch.mochaagents.tool.ConcurrentSafeBatcher(toolRegistry);
                        List<io.sketch.mochaagents.tool.ConcurrentSafeBatcher.ToolCall> calls = actions.stream()
                                .map(a -> new io.sketch.mochaagents.tool.ConcurrentSafeBatcher.ToolCall(
                                        a.name(), a.arguments()))
                                .toList();
                        results = batcher.execute(calls);
                    }

                    // Build observation and tool result blocks
                    StringBuilder obsBuilder = new StringBuilder();
                    for (int i = 0; i < results.size(); i++) {
                        var r = results.get(i);
                        // Find matching ToolUseBlock for tool_result pairing
                        String toolUseId = null, toolName = actions.get(i).name();
                        for (var block : assistantBlocks) {
                            if (block instanceof io.sketch.mochaagents.message.ContentBlock.ToolUseBlock tu
                                    && tu.name().equals(toolName)) {
                                toolUseId = tu.id();
                                break;
                            }
                        }
                        if (toolUseId != null) {
                            toolResultBlocks.add(r.isError()
                                    ? io.sketch.mochaagents.message.ContentBlock.ToolResultBlock.error(
                                            toolUseId, toolName, r.error())
                                    : io.sketch.mochaagents.message.ContentBlock.ToolResultBlock.success(
                                            toolUseId, toolName, String.valueOf(r.output())));
                        }
                        if (!obsBuilder.isEmpty()) obsBuilder.append("\n");
                        obsBuilder.append(r.isError() ? "Error: " + r.error() : String.valueOf(r.output()));
                        if ("final_answer".equals(actions.get(i).name())) {
                            isFinalAnswer = true;
                            toolResult = r.output();
                        }
                    }
                    observation = obsBuilder.toString();
                } else {
                    observation = "No tool calls found in typed response.";
                }
            } else {
                // ── Fallback: regex parsing on flat text ──
                ParsedAction action = parseAction(modelOutput);

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
            }

            ActionStep actionStep = new ActionStep(
                    stepNumber, typedMsgs.toString(), modelOutput,
                    isFinalAnswer ? "final_answer" : "tool_call",
                    observation, null,
                    response.promptTokens(), response.completionTokens(), isFinalAnswer,
                    assistantBlocks, toolResultBlocks);
            memory.appendAction(actionStep);

            if (isFinalAnswer) {
                memory.appendFinalAnswer(toolResult);
                log.info("[ToolCallingAgent] step {} final_answer: {}",
                        stepNumber, truncate(String.valueOf(toolResult), 200));
            }

            return StepResult.builder()
                    .stepNumber(stepNumber)
                    .state(isFinalAnswer ? LoopState.COMPLETE : LoopState.ACT)
                    .action(isFinalAnswer ? "final_answer" : "tool_call")
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

    private final io.sketch.mochaagents.tool.ConcurrentSafeBatcher toolBatcher =
            new io.sketch.mochaagents.tool.ConcurrentSafeBatcher(toolRegistry);

    public static Builder builder() { return new Builder(); }

    public static final class Builder extends ReActAgent.Builder<Builder> {
        @Override public ToolCallingAgent build() { return new ToolCallingAgent(this); }
    }
}
