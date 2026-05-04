package io.sketch.mochaagents.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.StreamingResponse;

import java.util.List;
import java.util.Map;

/**
 * Anthropic Claude LLM 提供者 — 通过 Messages API 调用.
 *
 * <pre>{@code
 * AnthropicLLM llm = AnthropicLLM.builder()
 *         .modelId("claude-sonnet-4-20250514")
 *         .apiKey(System.getenv("ANTHROPIC_API_KEY"))
 *         .build();
 * }</pre>
 * @author lanxia39@163.com
 */
public class AnthropicLLM extends BaseApiLLM {

    private final String apiKey;
    private final String baseUrl;
    private final String anthropicVersion;
    private final boolean usePromptCaching;

    protected AnthropicLLM(AnthropicBuilder builder) {
        super(builder);
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.anthropicVersion = builder.anthropicVersion;
        this.usePromptCaching = builder.usePromptCaching;

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Anthropic API key not set. Set ANTHROPIC_API_KEY or pass apiKey to builder.");
        }
    }

    @Override
    protected Map<String, String> authHeaders() {
        return Map.of("x-api-key", apiKey, "anthropic-version", anthropicVersion);
    }

    @Override
    protected String apiUrl() {
        return baseUrl + "/messages";
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
        body.put("max_tokens", request.maxTokens() > 0 ? request.maxTokens() : 4096);

        if (stream) {
            body.put("stream", true);
        }

        double temp = request.temperature() >= 0 ? request.temperature() : 1.0;
        body.put("temperature", Math.max(temp, 0.0001));

        if (request.topP() >= 0) body.put("top_p", request.topP());

        if (!request.stopSequences().isEmpty()) {
            ArrayNode stops = JSON.createArrayNode();
            request.stopSequences().forEach(stops::add);
            body.set("stop_sequences", stops);
        }

        // Apply ThinkingConfig — flows from Reasoner → ReActAgent → LLMRequest → Provider
        var thinkingConfig = request.thinkingConfig();
        if (thinkingConfig != null && thinkingConfig.type() != io.sketch.mochaagents.reasoning.ThinkingConfig.Type.DISABLED) {
            ObjectNode thinking = JSON.createObjectNode();
            if (thinkingConfig.type() == io.sketch.mochaagents.reasoning.ThinkingConfig.Type.ADAPTIVE) {
                thinking.put("type", "adaptive");
            } else if (thinkingConfig.type() == io.sketch.mochaagents.reasoning.ThinkingConfig.Type.ENABLED) {
                thinking.put("type", "enabled");
                thinking.put("budget_tokens", thinkingConfig.budgetTokens());
            }
            body.set("thinking", thinking);
        }

        List<Map<String, String>> messages = request.messages();
        ArrayNode anthropicMessages = JSON.createArrayNode();
        String systemPrompt = null;

        for (Map<String, String> msg : messages) {
            String role = msg.getOrDefault("role", "user");
            String content = msg.getOrDefault("content", "");

            if ("system".equals(role)) {
                systemPrompt = content;
            } else if ("assistant".equals(role) || "user".equals(role)) {
                ObjectNode am = JSON.createObjectNode();
                am.put("role", role);
                am.put("content", content);
                anthropicMessages.add(am);
            } else if ("tool-call".equals(role)) {
                ObjectNode am = JSON.createObjectNode();
                am.put("role", "assistant");
                am.put("content", content);
                anthropicMessages.add(am);
            } else if ("tool-response".equals(role)) {
                ObjectNode am = JSON.createObjectNode();
                am.put("role", "user");
                am.put("content", content != null ? content : "Tool result");
                anthropicMessages.add(am);
            }
        }

        if (anthropicMessages.isEmpty() && request.prompt() != null && !request.prompt().isEmpty()) {
            ObjectNode am = JSON.createObjectNode();
            am.put("role", "user");
            am.put("content", request.prompt());
            anthropicMessages.add(am);
        }

        body.set("messages", anthropicMessages);

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            // Use cache_control for ~90% cost reduction on cached system prompts
            if (usePromptCaching) {
                ObjectNode cachedSystem = JSON.createObjectNode();
                cachedSystem.put("type", "text");
                cachedSystem.put("text", systemPrompt);
                ObjectNode cacheControl = cachedSystem.putObject("cache_control");
                cacheControl.put("type", "ephemeral");
                ArrayNode systemArray = JSON.createArrayNode();
                systemArray.add(cachedSystem);
                body.set("system", systemArray);
            } else {
                body.put("system", systemPrompt);
            }
        }

        for (var entry : request.extraParams().entrySet()) {
            body.putPOJO(entry.getKey(), entry.getValue());
        }

        return body.toString();
    }

    /**
     * Anthropic SSE format uses named events. Text tokens arrive as
     * {@code event: content_block_delta} with {@code delta.text}.
     */
    @Override
    protected void parseSseData(String jsonData, StreamingResponse response) {
        try {
            JsonNode root = JSON.readTree(jsonData);
            JsonNode delta = root.get("delta");
            if (delta != null) {
                JsonNode text = delta.get("text");
                if (text != null && !text.isNull()) {
                    response.push(text.asText());
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse Anthropic SSE data: {}", e.getMessage());
        }
    }

    @Override
    protected ResponseParseResult parseResponseContent(JsonNode root) {
        // Anthropic 响应格式: {content: [{type: "text", text: "..."}], usage: {...}}
        JsonNode contentBlocks = root.get("content");
        StringBuilder content = new StringBuilder();

        if (contentBlocks != null && contentBlocks.isArray()) {
            for (JsonNode block : contentBlocks) {
                String type = safeStr(block, "type");
                if ("text".equals(type)) {
                    if (content.length() > 0) content.append("\n");
                    content.append(safeStr(block, "text"));
                } else if ("tool_use".equals(type)) {
                    if (content.length() > 0) content.append("\n");
                    content.append("[tool_use: ").append(safeStr(block, "name"))
                            .append("(").append(block.get("input")).append(")]");
                }
            }
        }

        JsonNode usage = root.get("usage");
        int inputTokens = usage != null ? safeInt(usage, "input_tokens") : 0;
        int outputTokens = usage != null ? safeInt(usage, "output_tokens") : 0;

        return new ResponseParseResult(content.toString(), inputTokens, outputTokens);
    }

    // ============ Builder ============

    public static AnthropicBuilder builder() {
        return new AnthropicBuilder();
    }

    public static final class AnthropicBuilder extends Builder<AnthropicBuilder> {
        private String apiKey = System.getenv("ANTHROPIC_API_KEY");
        private String baseUrl = "https://api.anthropic.com/v1";
        private String anthropicVersion = "2023-06-01";
        private boolean usePromptCaching = true; // default on — 90% cost reduction on system prompts

        public AnthropicBuilder apiKey(String key) { this.apiKey = key; return this; }
        public AnthropicBuilder baseUrl(String url) { this.baseUrl = url; return this; }
        public AnthropicBuilder anthropicVersion(String ver) { this.anthropicVersion = ver; return this; }
        public AnthropicBuilder promptCaching(boolean v) { this.usePromptCaching = v; return this; }

        public AnthropicLLM build() {
            if (modelId == null) modelId = "claude-sonnet-4-20250514";
            return new AnthropicLLM(this);
        }
    }
}
