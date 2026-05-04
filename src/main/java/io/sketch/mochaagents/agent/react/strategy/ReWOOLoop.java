package io.sketch.mochaagents.agent.react.strategy;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.agent.MemoryProvider;
import io.sketch.mochaagents.agent.react.*;
import io.sketch.mochaagents.memory.AgentMemory;
import java.util.function.Predicate;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ReWOO — REasoning WithOut Observation.
 *
 * <p>Unlike ReAct (which interleaves reasoning and acting), ReWOO separates the two:
 *
 * <pre>
 *   Phase 1 — REASON: build the full execution plan with placeholders for tool results
 *   Phase 2 — EXECUTE: batch-execute all tools, substituting placeholders with real results
 *   Phase 3 — SYNTHESIZE: combine all results into final answer
 * </pre>
 *
 * <p>Key advantage: fewer LLM calls (only 2 total), faster execution.
 * Best for tasks where the plan is clear and doesn't need per-step course correction.
 *
 * <p>Reference: Xu et al. "ReWOO: Decoupling Reasoning from Observations for Efficient Augmented Language Models"
 *
 * @author lanxia39@163.com
 */
public class ReWOOLoop<I, O> implements AgenticLoop<I, O> {

    private static final Logger log = LoggerFactory.getLogger(ReWOOLoop.class);

    @FunctionalInterface
    public interface Reasoner {
        /** Generate the complete execution plan with placeholders like #{E0}, #{E1}. */
        String reason(String task, AgentMemory memory);
    }

    @FunctionalInterface
    public interface ToolExecutor {
        /** Execute a tool with arguments, return the result. */
        String execute(String toolName, Map<String, Object> arguments);
    }

    @FunctionalInterface
    public interface Synthesizer {
        /** Combine all plan + tool results into a final answer. */
        String synthesize(String plan, List<String> toolResults, AgentMemory memory);
    }

    private final Reasoner reasoner;
    private final ToolExecutor toolExecutor;
    private final Synthesizer synthesizer;

    public ReWOOLoop(Reasoner reasoner, ToolExecutor toolExecutor, Synthesizer synthesizer) {
        this.reasoner = reasoner;
        this.toolExecutor = toolExecutor;
        this.synthesizer = synthesizer;
    }

    @Override
    public O run(Agent<I, O> agent, I input, Predicate<StepResult> condition) {
        String agentName = agent.metadata().name();
        AgentMemory memory = getMemory(agent);
        String task = input != null ? input.toString() : "";
        log.info("[{}] ReWOO loop starting, task={}", agentName, truncate(task, 80));

        try {
            // ===== Phase 1: REASON — build the full execution plan =====
            long phase1Start = System.currentTimeMillis();
            String plan = reasoner.reason(task, memory);

            if (memory != null) {
                memory.appendPlanning(plan, "[ReWOO plan]", 0, 0);
            }

            // Parse tool calls from the plan: ToolName[arg1, arg2] → #{E0}
            List<ToolCall> toolCalls = parseToolCalls(plan);
            log.info("[{}] Phase 1 REASON: {} tool calls planned in {}ms",
                    agentName, toolCalls.size(), System.currentTimeMillis() - phase1Start);

            // ===== Phase 2: EXECUTE — batch-execute all tools =====
            long phase2Start = System.currentTimeMillis();
            List<String> toolResults = new ArrayList<>();
            Map<String, String> placeholders = new LinkedHashMap<>();

            for (int i = 0; i < toolCalls.size(); i++) {
                ToolCall tc = toolCalls.get(i);
                // Substitute placeholders in arguments
                Map<String, Object> resolvedArgs = resolveArgs(tc.arguments, placeholders);

                try {
                    String result = toolExecutor.execute(tc.toolName, resolvedArgs);
                    String placeholder = "#{E" + i + "}";
                    placeholders.put(placeholder, result);
                    toolResults.add(placeholder + " = " + truncate(result, 200));

                    if (memory != null) {
                        memory.append(new io.sketch.mochaagents.agent.react.step.ActionStep(
                                i + 1, plan, tc.toolName + "(" + tc.arguments + ")",
                                tc.toolName, "Observation[" + i + "]: " + result,
                                null, 0, 0, false));
                    }
                } catch (Exception e) {
                    String placeholder = "#{E" + i + "}";
                    placeholders.put(placeholder, "Error: " + e.getMessage());
                    toolResults.add(placeholder + " = Error: " + e.getMessage());
                    log.warn("[{}] ReWOO tool '{}' failed: {}", agentName, tc.toolName, e.getMessage());
                }
            }

            log.info("[{}] Phase 2 EXECUTE: {} tools completed in {}ms",
                    agentName, toolCalls.size(), System.currentTimeMillis() - phase2Start);

            // ===== Phase 3: SYNTHESIZE — combine all results into final answer =====
            String answer = synthesizer.synthesize(plan, toolResults, memory);

            if (memory != null) {
                memory.appendFinalAnswer(answer);
            }

            log.info("[{}] Phase 3 SYNTHESIZE: final answer ({} chars)", agentName, answer.length());

            @SuppressWarnings("unchecked")
            O output = (O) (Object) answer;
            return output;

        } catch (Exception e) {
            log.error("[{}] ReWOO loop failed: {}", agentName, e.getMessage());
            if (memory != null) {
                memory.append(new io.sketch.mochaagents.agent.react.step.ActionStep(
                        0, "", "", "rewwo_error", e.getMessage(), null, 0, 0, true));
            }
            @SuppressWarnings("unchecked")
            O output = (O) (Object) ("ReWOO error: " + e.getMessage());
            return output;
        }
    }

    @Override
    public StepResult step(Agent<I, O> agent, I input, int stepNum) {
        // ReWOO doesn't have steps — the full run is atomic
        O result = run(agent, input, r -> true);
        return StepResult.builder()
                .stepNumber(stepNum)
                .state(LoopState.COMPLETE)
                .output(result != null ? result.toString() : "rewwo complete")
                .build();
    }

    // ============ Tool call parsing ============

    /** Parse tool calls from the plan. Format: ToolName[arg1="val1", arg2="val2"] → #{E0} */
    static List<ToolCall> parseToolCalls(String plan) {
        List<ToolCall> calls = new ArrayList<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(\\w+)\\[([^\\]]*)\\]\\s*(?:→\\s*#\\{(E\\d+)\\})?");
        java.util.regex.Matcher m = p.matcher(plan);

        while (m.find()) {
            String name = m.group(1);
            String argsStr = m.group(2);
            Map<String, Object> args = parseArgs(argsStr);
            calls.add(new ToolCall(name, args));
        }

        // If no structured calls found, try simple format: ToolName(arguments)
        if (calls.isEmpty()) {
            java.util.regex.Pattern simple = java.util.regex.Pattern.compile(
                    "(\\w+)\\(([^)]*)\\)");
            java.util.regex.Matcher sm = simple.matcher(plan);
            while (sm.find()) {
                String name = sm.group(1);
                String argsStr = sm.group(2);
                if (!name.equals("final_answer") && !name.equals("print")) {
                    Map<String, Object> args = parseArgs(argsStr);
                    calls.add(new ToolCall(name, args));
                }
            }
        }

        return calls;
    }

    private static Map<String, Object> parseArgs(String argsStr) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (argsStr == null || argsStr.isBlank()) return args;

        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(\\w+)\\s*=\\s*\"([^\"]*)\"");
        java.util.regex.Matcher m = p.matcher(argsStr);
        int positional = 0;
        while (m.find()) {
            args.put(m.group(1), m.group(2));
            positional++;
        }

        if (args.isEmpty() && !argsStr.isBlank()) {
            args.put("input", argsStr.replace("\"", "").trim());
        }
        return args;
    }

    /** Substitute placeholders #{E0} → actual result in arguments. */
    private static Map<String, Object> resolveArgs(Map<String, Object> args,
                                                    Map<String, String> placeholders) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (var entry : args.entrySet()) {
            String val = String.valueOf(entry.getValue());
            for (var ph : placeholders.entrySet()) {
                val = val.replace(ph.getKey(), ph.getValue());
            }
            resolved.put(entry.getKey(), val);
        }
        return resolved;
    }

    // ============ Types ============

    record ToolCall(String toolName, Map<String, Object> arguments) {}

    private static AgentMemory getMemory(Agent<?, ?> agent) {
        if (agent instanceof MemoryProvider mp) return mp.memory();
        return null;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
