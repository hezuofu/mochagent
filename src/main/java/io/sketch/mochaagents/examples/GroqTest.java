package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import io.sketch.mochaagents.llm.provider.OpenAICompatibleLLM;

/**
 * Groq 免费 API 直接连通性测试.
 * <p>前置条件: 注册 <a href="https://console.groq.com">console.groq.com</a> 获取免费 API Key,
 * 设置环境变量 {@code GROQ_API_KEY}.
 * <p>免费模型: llama-3.3-70b-versatile, mixtral-8x7b-32768, gemma2-9b-it, llama-3.1-8b-instant
 */
public class GroqTest {

    public static void main(String[] args) {
        String apiKey = System.getenv("GROQ_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("""
                    ╔══════════════════════════════════════════════════════════╗
                    ║  GROQ_API_KEY 未设置                                    ║
                    ║                                                        ║
                    ║  免费获取: https://console.groq.com/keys               ║
                    ║  然后运行: $env:GROQ_API_KEY='your-key'; mvn ...      ║
                    ╚══════════════════════════════════════════════════════════╝
                    """);
            return;
        }

        System.out.println("=== Groq 免费 API 连通性测试 ===\n");

        // 测试多个免费模型
        String[] models = {
                "llama-3.3-70b-versatile",
                "llama-3.1-8b-instant",
        };

        for (String model : models) {
            System.out.println("── 模型: " + model + " ──");
            try {
                var llm = OpenAICompatibleLLM.compatibleBuilder()
                        .modelId(model)
                        .baseUrl("https://api.groq.com/openai/v1")
                        .apiKey(apiKey)
                        .build();

                var request = LLMRequest.builder()
                        .prompt("用一句话介绍你自己.")
                        .build();

                long start = System.currentTimeMillis();
                LLMResponse response = llm.complete(request);
                long elapsed = System.currentTimeMillis() - start;

                System.out.println("  响应: " + response.content());
                System.out.println("  耗时: " + elapsed + "ms");
                System.out.println("  Token: prompt=" + response.promptTokens() + " completion=" + response.completionTokens());
            } catch (Exception e) {
                System.err.println("  错误: " + e.getMessage());
            }
            System.out.println();
        }

        System.out.println("=== 测试完成 ===");
    }
}
