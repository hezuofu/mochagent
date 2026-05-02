package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.core.impl.CodeAgent;
import io.sketch.mochaagents.llm.provider.MockLLM;
import io.sketch.mochaagents.tool.ToolRegistry;
import io.sketch.mochaagents.examples.tools.RetrieverTool;

import java.util.Map;

/**
 * Example04 — 对应 smolagents 的 rag.py.
 *
 * <p>构建带文档检索工具的 CodeAgent，模拟 RAG 场景.
 *
 * <pre>
 *   smolagents 对应:
 *     retriever_tool = RetrieverTool(docs_processed)
 *     agent = CodeAgent(tools=[retriever_tool], model=model, max_steps=4)
 *     agent.run("For a transformers model training, which is slower, forward or backward pass?")
 * </pre>
 */
public final class Example04_RAG {

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("Example04: RAG — 文档检索 Agent");
        System.out.println("=".repeat(60));

        // 模拟文档知识库
        Map<String, String> documents = Map.of(
                "transformers/training",
                "During transformers model training, the backward pass (gradient computation) "
                        + "is typically 2-3x slower than the forward pass due to the need to compute "
                        + "gradients for all parameters through back-propagation.",

                "transformers/attention",
                "The attention mechanism computes Q, K, V matrices and uses scaled dot-product "
                        + "attention for efficient sequence processing.",

                "transformers/inference",
                "At inference time, only the forward pass is needed, making it significantly faster "
                        + "than training. Key-value caching further optimizes autoregressive generation.",

                "java/overview",
                "Java is a high-level, class-based, object-oriented programming language designed "
                        + "to have as few implementation dependencies as possible."
        );

        var retrieverTool = new RetrieverTool(documents);
        var registry = new ToolRegistry();
        registry.register(retrieverTool);

        // MockLLM 识别 "forward" "backward" 关键词 → 输出 CodeAgent 代码块
        var agent = CodeAgent.builder()
                .name("rag-agent")
                .llm(LLMFactory.create())
                .toolRegistry(registry)
                .maxSteps(4)
                .build();

        String query = "For a transformers model training, which is slower, the forward or the backward pass?";
        System.out.println("\nQuery: " + query);

        String result = agent.run(query);
        System.out.println("Result: " + result);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Example04 Complete.");
    }

    private Example04_RAG() {}
}
