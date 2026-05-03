package io.sketch.mochaagents.agent.loop.strategy;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.agent.loop.AgenticLoop;
import io.sketch.mochaagents.agent.MemoryProvider;
import io.sketch.mochaagents.agent.loop.StepResult;
import io.sketch.mochaagents.agent.SystemPromptProvider;
import io.sketch.mochaagents.agent.loop.TerminationCondition;
import io.sketch.mochaagents.memory.AgentMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ReAct 循环 — {@link AgenticLoop} 的具体实现，执行 "思考→行动→观察" 迭代.
 *
 * <p>循环流程：
 * <ol>
 *   <li>生成/更新计划（可选）</li>
 *   <li>调用 LLM 生成行动</li>
 *   <li>执行工具/代码</li>
 *   <li>记录观察结果</li>
 *   <li>判断是否终止</li>
 * </ol>
 *
 * <p>依赖两个函数式接口注入：
 * <ul>
 *   <li>{@code planningFn} — 生成计划步骤</li>
 *   <li>{@code stepFn} — 执行单步（LLM 调用 + 工具/代码执行）</li>
 * </ul>
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 * @author lanxia39@163.com
 */
public class ReActLoop<I, O> implements AgenticLoop<I, O> {

    private static final Logger log = LoggerFactory.getLogger(ReActLoop.class);

    /**
     * 单步执行函数 — 一次 ReAct 迭代的完整实现.
     *
     * <p>输入 stepNumber 和 memory，返回 StepResult.
     */
    @FunctionalInterface
    public interface StepExecutor<I> {
        StepResult execute(int stepNumber, I input, AgentMemory memory);
    }

    /**
     * 规划函数 — 生成或更新计划.
     *
     * <p>返回规划步骤信息字符串，null 表示跳过规划.
     */
    @FunctionalInterface
    public interface PlanningFn<I> {
        String plan(int stepNumber, I input, AgentMemory memory);
    }

    private final PlanningFn<I> planningFn;
    private final StepExecutor<I> stepExecutor;
    private final int planningInterval;

    /**
     * @param planningFn     规划函数，可为 null 跳过规划
     * @param stepExecutor   单步执行函数
     * @param planningInterval 规划间隔（每隔多少步规划一次，0 或 null 表示不规划）
     */
    public ReActLoop(PlanningFn<I> planningFn, StepExecutor<I> stepExecutor, int planningInterval) {
        this.planningFn = planningFn;
        this.stepExecutor = stepExecutor;
        this.planningInterval = planningInterval;
    }

    public ReActLoop(StepExecutor<I> stepExecutor) {
        this(null, stepExecutor, 0);
    }

    @Override
    public O run(Agent<I, O> agent, I input, TerminationCondition condition) {
        long loopStart = System.currentTimeMillis();
        String agentName = agentName(agent);
        log.info("[{}] ReAct loop starting", agentName);

        // 使用 Agent 内部的 memory
        AgentMemory memory = getMemory(agent);
        if (memory != null) {
            memory.setSystemPrompt(buildSystemPrompt(agent));
        }

        int step = 1;
        int totalSteps = 0;
        StepResult result;
        do {
            // 可选规划
            if (planningFn != null && planningInterval > 0
                    && (step == 1 || (step - 1) % planningInterval == 0)) {
                log.info("[{}] step {}: generating plan", agentName, step);
                String planText = planningFn.plan(step, input, memory);
                if (planText != null && memory != null) {
                    memory.appendPlanning(planText, "", 0, 0);
                }
            }

            // 执行单步
            long stepStart = System.currentTimeMillis();
            result = stepExecutor.execute(step, input, memory);
            long stepMs = System.currentTimeMillis() - stepStart;
            totalSteps = step;
            log.info("[{}] step {}: action={}, state={}, duration={}ms",
                    agentName, step,
                    result != null ? result.action() : "?",
                    result != null ? result.state() : "?",
                    stepMs);
            step++;

        } while (!condition.shouldTerminate(result) && !isFinalAnswer(memory));

        long loopMs = System.currentTimeMillis() - loopStart;
        log.info("[{}] loop finished: {} steps in {}ms, final_state={}, terminated={}",
                agentName, totalSteps, loopMs,
                result != null ? result.state() : "null",
                condition.shouldTerminate(result));

        @SuppressWarnings("unchecked")
        O output = (O) result.output();
        return output;
    }

    @Override
    public StepResult step(Agent<I, O> agent, I input, int stepNum) {
        AgentMemory memory = getMemory(agent);
        return stepExecutor.execute(stepNum, input, memory);
    }

    // ============ 辅助方法 ============

    private static AgentMemory getMemory(Agent<?, ?> agent) {
        if (agent instanceof MemoryProvider mp) {
            return mp.memory();
        }
        return null;
    }

    private static String buildSystemPrompt(Agent<?, ?> agent) {
        if (agent instanceof SystemPromptProvider spp) {
            return spp.buildSystemPrompt();
        }
        return "";
    }

    private static boolean isFinalAnswer(AgentMemory memory) {
        return memory != null && memory.hasFinalAnswer();
    }

    private static String agentName(Agent<?, ?> agent) {
        return agent.metadata().name();
    }
}
