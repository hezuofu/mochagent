package io.sketch.mochaagents.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.util.Map;

/**
 * OpenAI LLM 提供者 — 通过 Chat Completions API 调用 OpenAI 模型.
 *
 * <pre>{@code
 * // 从环境变量读取 API Key
 * OpenAILLM llm = OpenAILLM.builder()
 *         .modelId("gpt-4o")
 *         .apiKey(System.getenv("OPENAI_API_KEY"))
 *         .build();
 *
 * // 或使用自定义端点 (Azure / 代理)
 * OpenAILLM llm = OpenAILLM.builder()
 *         .modelId("gpt-4o-mini")
 *         .apiKey("sk-...")
 *         .baseUrl("https://api.openai.com/v1")
 *         .build();
 * }</pre>
 * @author lanxia39@163.com
 */
public class OpenAILLM extends BaseApiLLM {

    private final String apiKey;
    private final String baseUrl;
    private final String organization;
    private final String project;

    protected OpenAILLM(OpenAIBuilder builder) {
        super(builder);
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.organization = builder.organization;
        this.project = builder.project;

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("OpenAI API key not set. Set OPENAI_API_KEY or pass apiKey to builder.");
        }
    }

    @Override
    protected void configureClient(OkHttpClient.Builder builder) {
        builder.addInterceptor(chain -> {
            Request original = chain.request();
            Request.Builder reqBuilder = original.newBuilder()
                    .header("Authorization", "Bearer " + apiKey);
            if (organization != null && !organization.isEmpty()) {
                reqBuilder.header("OpenAI-Organization", organization);
            }
            if (project != null && !project.isEmpty()) {
                reqBuilder.header("OpenAI-Project", project);
            }
            return chain.proceed(reqBuilder.build());
        });
    }

    @Override
    protected String apiUrl() {
        return baseUrl + "/chat/completions";
    }

    @Override
    protected String buildRequestBody(LLMRequest request) {
        return buildRequestBody(request, false);
    }

    @Override
    protected String buildStreamRequestBody(LLMRequest request) {
        return buildRequestBody(request, true);
    }

    private String buildRequestBody(LLMRequest request, boolean stream) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", modelId);

        if (stream) {
            body.put("stream", true);
        }

        // 消息
        ArrayNode messages = messagesToJson(request.messages());
        if (messages.isEmpty()) {
            ObjectNode userMsg = JSON.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", request.prompt() != null ? request.prompt() : "");
            messages.add(userMsg);
        }
        body.set("messages", messages);

        // 参数
        if (request.maxTokens() > 0) body.put("max_tokens", request.maxTokens());
        if (request.temperature() >= 0) body.put("temperature", request.temperature());
        if (request.topP() >= 0) body.put("top_p", request.topP());

        // stop sequences
        if (!request.stopSequences().isEmpty()) {
            ArrayNode stops = JSON.createArrayNode();
            request.stopSequences().forEach(stops::add);
            body.set("stop", stops);
        }

        // extra params
        for (var entry : request.extraParams().entrySet()) {
            body.putPOJO(entry.getKey(), entry.getValue());
        }

        return body.toString();
    }

    @Override
    protected ResponseParseResult parseResponseContent(JsonNode root) {
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new LLMException("No choices in response: " + root);
        }

        JsonNode message = choices.get(0).get("message");
        String content = "";
        if (message != null) {
            content = safeStr(message, "content");
            // 函数调用时 content 可能为 null
            if (content.isEmpty() && message.has("tool_calls")) {
                content = message.get("tool_calls").toString();
            }
        }

        JsonNode usage = root.get("usage");
        int promptTokens = usage != null ? safeInt(usage, "prompt_tokens") : 0;
        int completionTokens = usage != null ? safeInt(usage, "completion_tokens") : 0;

        return new ResponseParseResult(content, promptTokens, completionTokens);
    }

    @Override
    public String modelName() {
        return modelId;
    }

    // ============ Builder ============

    public static OpenAIBuilder builder() {
        return new OpenAIBuilder();
    }

    public static final class OpenAIBuilder extends Builder<OpenAIBuilder> {
        private String apiKey = System.getenv("OPENAI_API_KEY");
        private String baseUrl = "https://api.openai.com/v1";
        private String organization;
        private String project;

        public OpenAIBuilder apiKey(String key) { this.apiKey = key; return this; }
        public OpenAIBuilder baseUrl(String url) { this.baseUrl = url; return this; }
        public OpenAIBuilder organization(String org) { this.organization = org; return this; }
        public OpenAIBuilder project(String proj) { this.project = proj; return this; }

        public OpenAILLM build() {
            if (modelId == null) modelId = "gpt-4o";
            return new OpenAILLM(this);
        }
    }
}
