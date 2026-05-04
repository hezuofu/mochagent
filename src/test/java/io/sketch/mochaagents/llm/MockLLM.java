package io.sketch.mochaagents.llm;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import io.sketch.mochaagents.llm.StreamingResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * MockLLM — 模拟 LLM 响应，用于示例演示和测试.
 *
 * <p>支持自定义响应生成器，可注入 {@code responseFn} 控制每次调用的输出.
 * 不依赖外部 API，适合本地运行示例.
 * @author lanxia39@163.com
 */
public class MockLLM implements LLM {

    private final String modelName;
    private final int maxTokens;
    private final Function<LLMRequest, String> responseFn;

    private MockLLM(Builder builder) {
        this.modelName = builder.modelName;
        this.maxTokens = builder.maxTokens;
        this.responseFn = builder.responseFn;
    }

    @Override
    public LLMResponse complete(LLMRequest request) {
        String content = responseFn != null
                ? responseFn.apply(request)
                : defaultResponse(request);
        return new LLMResponse(content, modelName, 10, content.length() / 4, 50, Map.of());
    }

    @Override
    public CompletableFuture<LLMResponse> completeAsync(LLMRequest request) {
        return CompletableFuture.completedFuture(complete(request));
    }

    @Override
    public StreamingResponse stream(LLMRequest request) {
        StreamingResponse stream = new StreamingResponse();
        String content = complete(request).content();
        for (String token : content.split(" ")) {
            stream.push(token + " ");
        }
        stream.complete();
        return stream;
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public int maxContextTokens() {
        return maxTokens;
    }

    // ============ 默认响应（识别 Task 内容） ============

    private String defaultResponse(LLMRequest request) {
        String lastMsg = lastUserMessage(request);
        if (lastMsg == null) lastMsg = "";

        String lower = lastMsg.toLowerCase();

        // Step 2+: 检测 Observation，给出最终答案
        if (lower.startsWith("observation:") || lower.contains("observation:") || lower.contains("execution logs:")) {
            // 判断是 ToolCallingAgent 还是 CodeAgent 上下文
            if (isCodeAgentContext(request)) {
                return "<code>\n"
                        + "final_answer(\"Task completed based on execution results.\")\n"
                        + "</code>";
            }
            return "Thought: I have the information needed.\n"
                    + "Action: final_answer(answer=\"Based on the observations, the task is complete.\")";
        }

        if (lower.contains("weather") || lower.contains("巴黎") || lower.contains("paris") || lower.contains("tokyo")) {
            if (isCodeAgentContext(request)) {
                return "<code>\n"
                        + "# Weather check code block\n"
                        + "final_answer(\"The weather in Paris is cloudy with torrential rains, temperature 10°F.\")\n"
                        + "</code>";
            }
            return "Thought: I need to check the weather. Let me call the weather tool.\n"
                    + "Action: get_weather(location=\"Paris\")";
        }
        if (lower.contains("joke") || lower.contains("笑话")) {
            if (isCodeAgentContext(request)) {
                return "<code>\nfinal_answer(\"Why do Java developers wear glasses? Because they don't C#!\")\n</code>";
            }
            return "Thought: The user wants a joke.\n"
                    + "Action: get_joke()";
        }
        if (lower.contains("fact") || lower.contains("事实")) {
            if (isCodeAgentContext(request)) {
                return "<code>\nfinal_answer(\"Honey never spoils. 3000-year-old honey in Egyptian tombs was still edible.\")\n</code>";
            }
            return "Thought: Let me fetch a random fact.\n"
                    + "Action: get_random_fact()";
        }
        if (lower.contains("news") || lower.contains("新闻")) {
            if (isCodeAgentContext(request)) {
                return "<code>\nfinal_answer(\"Top headlines: AI Summit, Stock Records, Climate Pact, Open Source Alliance, Space Milestone.\")\n</code>";
            }
            return "Thought: Fetching latest headlines.\n"
                    + "Action: get_news_headlines()";
        }
        if (lower.contains("convert") || lower.contains("dollar") || lower.contains("euro")) {
            if (isCodeAgentContext(request)) {
                return "<code>\nfinal_answer(\"5000 USD = 4600.00 EUR at current exchange rate.\")\n</code>";
            }
            return "Thought: Let me convert the currency.\n"
                    + "Action: convert_currency(amount=\"5000\", from_currency=\"USD\", to_currency=\"EUR\")";
        }
        if (lower.contains("elon musk") || lower.contains("who is")) {
            if (isCodeAgentContext(request)) {
                return "<code>\nfinal_answer(\"Elon Musk is a notable topic widely studied in academic literature.\")\n</code>";
            }
            return "Thought: I'll search Wikipedia for this.\n"
                    + "Action: search_wikipedia(query=\"Elon Musk\")";
        }
        if (lower.contains("sql") || lower.contains("receipt") || lower.contains("expensive")) {
            return "<code>\n"
                    + "# SQL query agent\n"
                    + "result = sql_engine(query=\"SELECT customer_name, MAX(price) as max_price FROM receipts\")\n"
                    + "print(result)\n"
                    + "final_answer(result)\n"
                    + "</code>";
        }
        if (lower.contains("retriev") || lower.contains("document") || lower.contains("forward") || lower.contains("backward") || lower.contains("pass")) {
            return "<code>\n"
                    + "# RAG retrieval agent\n"
                    + "docs = retriever(query=\"transformers training forward backward pass speed comparison\")\n"
                    + "print(docs)\n"
                    + "final_answer(docs)\n"
                    + "</code>";
        }
        if (lower.contains("fibonacci") || lower.contains("计算")) {
            return "<code>\n"
                    + "result = 6765  # Fibonacci(20)\n"
                    + "final_answer(\"Fibonacci(20) = \" + str(result))\n"
                    + "</code>";
        }
        if (lower.contains("gdp") || lower.contains("growth") || lower.contains("double")) {
            return "Thought: I'll delegate the search to search_agent.\n"
                    + "Action: search_agent(query=\"US GDP growth rate 2024 doubling time\")";
        }

        // 默认: 尝试回答
        return "Thought: I will provide a final answer based on my knowledge.\n"
                + "Action: final_answer(answer=\"Task completed: " + lastMsg + "\")";
    }

    private String lastUserMessage(LLMRequest request) {
        var messages = request.messages();
        if (messages == null || messages.isEmpty()) return request.prompt();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                return messages.get(i).get("content");
            }
        }
        return request.prompt();
    }

    /** 检测是否为 CodeAgent 上下文（系统提示含 code/enclose 关键词）. */
    private boolean isCodeAgentContext(LLMRequest request) {
        var messages = request.messages();
        if (messages == null) return false;
        for (var msg : messages) {
            String content = msg.get("content");
            if (content != null && (content.contains("Enclose your code") || content.contains("code block") || content.contains("<code>"))) {
                return true;
            }
        }
        return false;
    }

    // ============ Builder ============

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建默认 MockLLM（自动识别 weather/joke/news/fact/sql 等任务）.
     */
    public static MockLLM create() {
        return builder().modelName("mock-gpt-4").maxContextTokens(8192).build();
    }

    /**
     * 创建带自定义响应函数的 MockLLM.
     */
    public static MockLLM with(Function<LLMRequest, String> responseFn) {
        return builder().modelName("mock-custom").responseFn(responseFn).build();
    }

    public static final class Builder {
        private String modelName = "mock-model";
        private int maxTokens = 8192;
        private Function<LLMRequest, String> responseFn;

        public Builder modelName(String name) { this.modelName = name; return this; }
        public Builder maxContextTokens(int tokens) { this.maxTokens = tokens; return this; }
        public Builder responseFn(Function<LLMRequest, String> fn) { this.responseFn = fn; return this; }

        public MockLLM build() {
            return new MockLLM(this);
        }
    }
}
