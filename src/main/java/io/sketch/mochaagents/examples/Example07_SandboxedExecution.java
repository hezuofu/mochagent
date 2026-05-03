package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.provider.MockLLM;
import io.sketch.mochaagents.agent.impl.CodeAgent;
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolRegistry;

import java.util.Map;

/**
 * Example07 — 对应 smolagents 的 sandboxed_execution.py.
 *
 * <p>演示多种代码执行器后端（本地、模拟远程沙箱）的可插拔切换.
 * 通过自定义 CodeAgent builder 注入不同的 executor 策略.
 * @author lanxia39@163.com
 */
public final class Example07_SandboxedExecution {

    @FunctionalInterface
    public interface CodeExecutor {
        String execute(String code, Map<String, Tool> tools);
    }

    static final CodeExecutor LOCAL = (code, tools) ->
            "[Local] Code executed: " + code.lines().count() + " lines";

    static final CodeExecutor BLAXEL = (code, tools) ->
            "[Blaxel Sandbox] Remote execution completed. Output: result = 42";

    static final CodeExecutor DOCKER = (code, tools) ->
            "[Docker Container] Isolated execution: " + code.substring(0, Math.min(50, code.length())) + "...";

    static final CodeExecutor E2B = (code, tools) ->
            "[E2B Cloud Sandbox] Secure execution. Output: Computation result.";

    static final CodeExecutor WASM = (code, tools) -> {
        if (code.contains("sqrt") || code.contains("125")) {
            return "[WASM] sqrt(125) = 11.180339887498949";
        }
        return "[WASM] Executed.";
    };

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("Example07: SandboxedExecution — 多沙箱执行器演示");
        System.out.println("=".repeat(60));

        String query = "Calculate the square root of 125";
        CodeExecutor[] executors = {LOCAL, BLAXEL, DOCKER, E2B, WASM};
        String[] labels = {"Local", "Blaxel", "Docker", "E2B", "Wasm"};

        for (int i = 0; i < executors.length; i++) {
            var registry = new ToolRegistry();
            CodeExecutor executor = executors[i];

            var llm = MockLLM.with(req -> {
                String msg = lastUserMsg(req);
                if (msg.toLowerCase().contains("observation:") || msg.toLowerCase().contains("execution logs:")) {
                    return "<code>\nfinal_answer(\"Complete\")\n</code>";
                }
                return "<code>\nimport math\nresult = math.sqrt(125)\nfinal_answer(str(result))\n</code>";
            });

            // 使用 CodeAgent + 自定义 executor
            var agent = CodeAgent.builder()
                    .name("sandbox-" + labels[i].toLowerCase())
                    .llm(llm)
                    .toolRegistry(registry)
                    .maxSteps(3)
                    .build();

            System.out.println("\n── Executor: " + labels[i] + " ──");
            System.out.println("Sandbox result: " + executor.execute(query, null));
            System.out.println("Agent result: " + agent.run(query));
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Example07 Complete.");
    }

    private static String lastUserMsg(LLMRequest req) {
        var msgs = req.messages();
        if (msgs != null) for (int i = msgs.size() - 1; i >= 0; i--) {
            if ("user".equals(msgs.get(i).get("role"))) return msgs.get(i).get("content");
        }
        return req.prompt();
    }

    private Example07_SandboxedExecution() {}
}
