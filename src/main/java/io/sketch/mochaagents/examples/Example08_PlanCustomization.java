package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.agent.impl.CodeAgent;
import io.sketch.mochaagents.memory.AgentMemory;
import io.sketch.mochaagents.agent.react.step.MemoryStep;
import io.sketch.mochaagents.agent.react.step.PlanningStep;
import io.sketch.mochaagents.tool.ToolRegistry;

import java.util.Scanner;

/**
 * Example08 — 对应 smolagents 的 plan_customization.py.
 *
 * <p>演示步骤回调 + 规划中断/审批机制.
 * 在 PlanningStep 产生后暂停执行，允许用户审批或修改计划.
 *
 * <pre>
 *   smolagents 对应:
 *     def interrupt_after_plan(memory_step, agent):
 *         if isinstance(memory_step, PlanningStep):
 *             display_plan(memory_step.plan)
 *             choice = get_user_choice()  # 1=批准 2=修改 3=取消
 * </pre>
 * @author lanxia39@163.com
 */
public final class Example08_PlanCustomization {

    /** 步骤回调函数签名. */
    @FunctionalInterface
    public interface StepCallback {
        /** 返回 true 表示应中断执行. */
        boolean onStep(MemoryStep step, Object agentContext);
    }

    /** 规划审批回调 — 在 PlanningStep 时询问用户. */
    static final class PlanApprovalCallback implements StepCallback {
        private final Scanner scanner = new Scanner(System.in);

        @Override
        public boolean onStep(MemoryStep step, Object agentContext) {
            if (!(step instanceof PlanningStep ps)) return false;

            System.out.println("\n" + "=".repeat(50));
            System.out.println("📋 AGENT PLAN CREATED");
            System.out.println("=".repeat(50));
            System.out.println(ps.plan());
            System.out.println("=".repeat(50));

            while (true) {
                System.out.print("\nChoose: [1] Approve  [2] Modify  [3] Cancel: ");
                String choice = scanner.nextLine().trim();
                switch (choice) {
                    case "1":
                        System.out.println("✅ Plan approved. Continuing...");
                        return false;
                    case "2":
                        System.out.print("Enter modified plan: ");
                        String modified = scanner.nextLine().trim();
                        System.out.println("📝 Plan updated to: " + modified);
                        return false;
                    case "3":
                        System.out.println("❌ Execution cancelled.");
                        return true;
                    default:
                        System.out.println("Invalid choice.");
                }
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("Example08: PlanCustomization — 规划审批/中断回调");
        System.out.println("=".repeat(60));

        var registry = new ToolRegistry();
        StepCallback callback = new PlanApprovalCallback();

        // 模拟环境：固定审批计划（非交互模式提示）
        System.out.println("\n⚠  This demo runs in auto-approve mode (non-interactive).");
        System.out.println("   In interactive mode, the agent pauses after planning.\n");

        // 使用 auto-approve callback 演示回调机制
        StepCallback autoApprove = (step, ctx) -> {
            if (step instanceof PlanningStep ps) {
                System.out.println("📋 Plan detected: " + ps.plan());
                System.out.println("✅ Auto-approved.\n");
            }
            return false;
        };

        runWithCallback(autoApprove, registry);

        System.out.println("=".repeat(60));
        System.out.println("Example08 Complete.");
    }

    private static void runWithCallback(StepCallback callback, ToolRegistry registry) {
        var llm = LLMFactory.create();

        // 模拟带规划间隔的 Agent
        var agent = CodeAgent.builder()
                .name("plan-agent")
                .llm(llm)
                .toolRegistry(registry)
                .maxSteps(3)
                .planningInterval(1)
                .planningPromptTemplate(
                        io.sketch.mochaagents.prompt.PromptTemplate.of(
                                "Create a brief plan to answer: {task}\nTools: {tools}"))
                .build();

        String task = "Search for recent AI developments and summarize top 3 breakthroughs.";
        System.out.println("Task: " + task + "\n");

        // 在 run 前后注入回调
        String result = agent.run(task);

        // 扫描 memory 中的 PlanningStep，触发回调
        AgentMemory memory = agent.memory();
        for (MemoryStep step : memory.steps()) {
            boolean shouldStop = callback.onStep(step, agent);
            if (shouldStop) {
                System.out.println("Execution interrupted by callback.");
                return;
            }
        }

        System.out.println("Final result: " + result);
    }

    private Example08_PlanCustomization() {}
}
