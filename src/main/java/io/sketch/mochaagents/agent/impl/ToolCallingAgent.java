package io.sketch.mochaagents.agent.impl;

import io.sketch.mochaagents.agent.loop.LoopState;
import io.sketch.mochaagents.agent.loop.StepResult;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import io.sketch.mochaagents.memory.AgentMemory;
import io.sketch.mochaagents.agent.loop.step.ActionStep;
import io.sketch.mochaagents.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ToolCallingAgent — 使用 LLM 生成工具调用并执行的 ReAct Agent.
 *
 * <p>执行流程：
 * <ol>
 *   <li>将记忆写入 LLM 消息</li>
 *   <li>LLM 输出 "Thought: ... Action: tool_name(arguments)"</li>
 *   <li>解析工具名和参数</li>
 *   <li>执行工具调用</li>
 *   <li>记录观察结果</li>
 *   <li>若调用 final_answer 则终止</li>
 * </ol>
 *
 * <p>对应 smolagents 的 {@code ToolCallingAgent}.
 */
public final class ToolCallingAgent extends MultiStepAgent {

    private static final Logger log = LoggerFactory.getLogger(ToolCallingAgent.class);

    private static final Pattern ACTION_PATTERN =
            Pattern.compile("Action:\\s*(\\w+)\\((.*?)\\)", Pattern.DOTALL);
    private static final Pattern JSON_ACTION_PATTERN =
            Pattern.compile("\"name\"\\s*:\\s*\"(\\w+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{[^}]+\\})");

    private ToolCallingAgent(Builder builder) {
        super(builder);
    }

    @Override
    public String buildSystemPrompt() {
        if (systemPromptTemplate != null && !systemPromptTemplate.template().isEmpty()) {
            return systemPromptTemplate.render(Map.of(
                    "tools", formatTools(),
                    "managed_agents", formatManagedAgents(),
                    "instructions", description != null ? description : ""
            ));
        }
        // 默认系统提示
        return """
                You are an AI assistant that solves tasks step by step.
                You have access to the following tools:
                {tools}

                For each step, respond in this exact format:
                Thought: <your reasoning about what to do next>
                Action: <tool_name>(<arguments>)

                When you have the final answer, use:
                Action: final_answer(answer="your answer")

                After each action, you will receive an Observation.
                Use these observations to decide your next action.
                """.replace("{tools}", formatTools());
    }

    @Override
    protected StepResult executeReActStep(int stepNumber, String input, AgentMemory memory) {
        long start = System.currentTimeMillis();

        try {
            // 1. 构建 LLM 消息
            List<Map<String, String>> messages = writeMemoryToMessages();

            // 2. 调用 LLM
            LLMRequest request = LLMRequest.builder()
                    .messages(messages)
                    .maxTokens(2048)
                    .temperature(0.7)
                    .build();

            long llmStart = System.currentTimeMillis();
            LLMResponse response = llm.complete(request);
            long llmMs = System.currentTimeMillis() - llmStart;
            String modelOutput = response.content();
            log.info("[ToolCallingAgent] step {} LLM call: {}ms, tokens in={} out={}",
                    stepNumber, llmMs, response.promptTokens(), response.completionTokens());

            // 3. 解析动作
            ParsedAction action = parseAction(modelOutput);

            // 4. 执行工具
            String observation;
            boolean isFinalAnswer = false;
            Object toolResult = null;

            if (action != null && toolRegistry != null && toolRegistry.has(action.name)) {
                Tool tool = toolRegistry.get(action.name);
                try {
                    long toolStart = System.currentTimeMillis();
                    toolResult = tool.call(action.arguments);
                    long toolMs = System.currentTimeMillis() - toolStart;
                    observation = String.valueOf(toolResult);
                    isFinalAnswer = "final_answer".equals(action.name);
                    log.debug("ToolCallingAgent step {} executed tool '{}' in {}ms",
                            stepNumber, action.name, toolMs);
                } catch (Exception e) {
                    log.warn("ToolCallingAgent step {} tool '{}' error: {}",
                            stepNumber, action.name, e.getMessage());
                    observation = "Tool error: " + e.getMessage();
                }
            } else if (action != null) {
                log.warn("ToolCallingAgent step {} tool '{}' not found", stepNumber, action.name);
                observation = "Tool not found: " + action.name
                        + ". Available: " + (toolRegistry != null
                        ? toolRegistry.all().stream().map(Tool::getName).toList() : "none");
            } else {
                observation = "Could not parse action from output. "
                        + "Use format: Action: tool_name(arguments)";
            }

            // 5. 记录 ActionStep（必须在 appendFinalAnswer 之前）
            ActionStep actionStep = new ActionStep(
                    stepNumber,
                    messages.toString(),
                    modelOutput,
                    action != null ? action.name + "(" + action.arguments + ")" : "parse_error",
                    observation,
                    null,
                    response.promptTokens(),
                    response.completionTokens(),
                    isFinalAnswer
            );
            memory.appendAction(actionStep);

            // 6. final_answer 记录为最后一步
            if (isFinalAnswer) {
                memory.appendFinalAnswer(toolResult);
                log.info("[ToolCallingAgent] step {} final_answer: {}",
                        stepNumber, truncate(String.valueOf(toolResult), 200));
            }

            log.debug("ToolCallingAgent step {} completed: action={}, finalAnswer={}",
                    stepNumber, action != null ? action.name : "parse_error", isFinalAnswer);
            return StepResult.builder()
                    .stepNumber(stepNumber)
                    .state(isFinalAnswer ? LoopState.COMPLETE : LoopState.ACT)
                    .action(action != null ? action.name : "parse_error")
                    .observation(observation)
                    .output(isFinalAnswer ? String.valueOf(toolResult) : observation)
                    .durationMs(System.currentTimeMillis() - start)
                    .build();

        } catch (Exception e) {
            log.error("ToolCallingAgent step {} failed", stepNumber, e);
            ActionStep errorStep = new ActionStep(
                    stepNumber, "", "", "", "",
                    e.getMessage(), 0, 0, false
            );
            memory.appendAction(errorStep);

            return StepResult.builder()
                    .stepNumber(stepNumber)
                    .state(LoopState.ERROR)
                    .error(e.getMessage())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    // ============ 动作解析 ============

    private ParsedAction parseAction(String modelOutput) {
        // 尝试 JSON 格式
        Matcher jsonMatcher = JSON_ACTION_PATTERN.matcher(modelOutput);
        if (jsonMatcher.find()) {
            String name = jsonMatcher.group(1);
            String argsStr = jsonMatcher.group(2);
            Map<String, Object> args = parseSimpleJson(argsStr);
            return new ParsedAction(name, args);
        }

        // 尝试 "Action: tool_name(arguments)" 格式
        Matcher m = ACTION_PATTERN.matcher(modelOutput);
        if (m.find()) {
            String name = m.group(1);
            String argsStr = m.group(2).trim();
            Map<String, Object> args = parseKeyValueArgs(argsStr);
            return new ParsedAction(name, args);
        }

        return null;
    }

    /** 解析简单 JSON 对象 { "key": "value" }. */
    private Map<String, Object> parseSimpleJson(String json) {
        Map<String, Object> result = new LinkedHashMap<>();
        Pattern pairPattern = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = pairPattern.matcher(json);
        while (m.find()) {
            result.put(m.group(1), m.group(2));
        }
        return result;
    }

    /** 解析 key=value 格式参数. */
    private Map<String, Object> parseKeyValueArgs(String args) {
        Map<String, Object> result = new LinkedHashMap<>();
        Pattern pairPattern = Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*)\"");
        Matcher m = pairPattern.matcher(args);
        while (m.find()) {
            result.put(m.group(1), m.group(2));
        }
        if (result.isEmpty() && !args.isEmpty()) {
            // 无 key=value 格式时，作为单个参数
            result.put("input", args.replace("\"", "").trim());
        }
        return result;
    }

    private record ParsedAction(String name, Map<String, Object> arguments) {}

    // ============ Builder ============

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends MultiStepAgent.Builder<Builder> {
        @Override
        public ToolCallingAgent build() {
            return new ToolCallingAgent(this);
        }
    }
}
