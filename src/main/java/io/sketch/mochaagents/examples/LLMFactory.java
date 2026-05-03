package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.provider.*;

/**
 * LLM 工厂 — 按环境变量自动选择可用的真实 Provider, 无可用时降级到 MockLLM.
 *
 * <p>检测顺序:
 * <ol>
 *   <li>{@code OPENAI_API_KEY} → OpenAILLM</li>
 *   <li>{@code ANTHROPIC_API_KEY} → AnthropicLLM</li>
 *   <li>{@code GROQ_API_KEY} → OpenAICompatibleLLM (Groq 免费层)</li>
 *   <li>{@code DEEPSEEK_API_KEY} → DeepSeekLLM</li>
 *   <li>{@code DASHSCOPE_API_KEY} → QwenLLM (通义千问)</li>
 *   <li>{@code HF_TOKEN} → OpenAICompatibleLLM (HuggingFace Inference, 免费层)</li>
 *   <li>{@code OLLAMA_HOST} 或本地检测 → LocalLLM</li>
 *   <li>降级 → MockLLM</li>
 * </ol>
 * @author lanxia39@163.com
 */
public final class LLMFactory {

    private static volatile LLM cached;

    /** 自动检测并返回最佳可用 LLM (结果缓存). */
    public static LLM create() {
        if (cached != null) return cached;
        return cached = createAuto();
    }

    /** 清除缓存, 重新检测. */
    public static void reset() {
        cached = null;
    }

    /** 创建新实例（不走缓存），用于多 LLM 路由等场景. */
    public static LLM createNew() {
        return createAuto();
    }

    private static LLM createAuto() {
        // 1. OpenAI
        String openaiKey = env("OPENAI_API_KEY");
        if (notBlank(openaiKey)) {
            System.out.println("[LLM] Detected OPENAI_API_KEY → using OpenAILLM");
            return OpenAILLM.builder().apiKey(openaiKey).build();
        }

        // 2. Anthropic
        String anthropicKey = env("ANTHROPIC_API_KEY");
        if (notBlank(anthropicKey)) {
            System.out.println("[LLM] Detected ANTHROPIC_API_KEY → using AnthropicLLM");
            return AnthropicLLM.builder().apiKey(anthropicKey).build();
        }

        // 3. Groq
        String groqKey = env("GROQ_API_KEY");
        if (notBlank(groqKey)) {
            System.out.println("[LLM] Detected GROQ_API_KEY → using Groq via OpenAICompatibleLLM");
            return OpenAICompatibleLLM.compatibleBuilder()
                    .modelId("llama-3.3-70b-versatile")
                    .baseUrl("https://api.groq.com/openai/v1")
                    .apiKey(groqKey)
                    .build();
        }

        // 4. DeepSeek
        String deepseekKey = env("DEEPSEEK_API_KEY");
        if (notBlank(deepseekKey)) {
            System.out.println("[LLM] Detected DEEPSEEK_API_KEY → using DeepSeekLLM");
            return DeepSeekLLM.create();
        }

        // 5. 通义千问 (DashScope)
        String dashscopeKey = env("DASHSCOPE_API_KEY");
        if (notBlank(dashscopeKey)) {
            System.out.println("[LLM] Detected DASHSCOPE_API_KEY → using QwenLLM");
            return QwenLLM.create();
        }

        // 6. HuggingFace Inference API (免费层, smolagents 主力)
        String hfToken = env("HF_TOKEN");
        if (hfToken == null) hfToken = env("HUGGINGFACE_HUB_TOKEN");
        if (notBlank(hfToken)) {
            System.out.println("[LLM] Detected HF_TOKEN → using HuggingFace Inference API");
            return OpenAICompatibleLLM.compatibleBuilder()
                    .modelId("Qwen/Qwen2.5-72B-Instruct")
                    .baseUrl("https://api-inference.huggingface.co/models/Qwen/Qwen2.5-72B-Instruct/v1")
                    .apiKey(hfToken)
                    .build();
        }

        // 7. Ollama 本地
        if (ollamaAvailable()) {
            System.out.println("[LLM] Detected local Ollama → using LocalLLM");
            return LocalLLM.create();
        }

        // 8. 降级
        System.out.println("[LLM] No real provider detected → falling back to MockLLM");
        return MockLLM.create();
    }

    // ============ 辅助方法 ============

    private static String env(String name) {
        try {
            return System.getenv(name);
        } catch (SecurityException e) {
            return null;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 检测本地 Ollama 是否可用. */
    private static boolean ollamaAvailable() {
        String host = env("OLLAMA_HOST");
        if (notBlank(host)) return true;
        try {
            var url = new java.net.URL("http://localhost:11434/api/tags");
            var conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private LLMFactory() {}
}
