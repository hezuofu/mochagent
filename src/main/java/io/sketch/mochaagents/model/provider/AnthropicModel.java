// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.model.provider;

import io.sketch.mochaagents.model.Model;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sketch.mochaagents.model.ModelRequest;
import io.sketch.mochaagents.model.StreamingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Claude Model 提供者 — 通过 Messages API 调用.
 *
 * <pre>{@code
 * AnthropicModel model = AnthropicModel.builder()
 *         .modelId("claude-sonnet-4-20250514")
 *         .apiKey(System.getenv("ANTHROPIC_API_KEY"))
 *         .build();
 * }</pre>
 * @author lanxia39@163.com
 */
public class AnthropicModel extends BaseApiModel implements Model.NativeTools {

    private final String apiKey;
    private final String baseUrl;
    private final String anthropicVersion;
    private final boolean usePromptCaching;

    protected AnthropicModel(AnthropicBuilder builder) {
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
    protected String buildRequestBody(ModelRequest request) {
        return buildRequestBody(request, false);
    }

    @Override
    protected String buildStreamRequestBody(ModelRequest request) {
        return buildRequestBody(request, true);
    }


    private String buildRequestBody(ModelRequest request, boolean stream) {
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

        // Apply ThinkingConfig — flows from Reasoner → ReActAgent → ModelRequest → Provider
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

        ArrayNode anthropicMessages = JSON.createArrayNode();
        String systemPrompt = null;

        // Typed-first: use ContentBlock-aware messages when available
        if (!request.typedMessages().isEmpty()) {
            ensureToolResultPairing(request.typedMessages());
            for (var msg : request.typedMessages()) {
                if (msg instanceof io.sketch.mochaagents.message.Message.SystemMessage s) {
                    systemPrompt = (systemPrompt == null ? "" : systemPrompt + "\n") + s.content();
                } else if (msg instanceof io.sketch.mochaagents.message.Message.UserMessage u) {
                    anthropicMessages.add(toAnthropicUserMessage(u));
                } else if (msg instanceof io.sketch.mochaagents.message.Message.AssistantMessage a) {
                    anthropicMessages.add(toAnthropicAssistantMessage(a));
                }
            }
        } else {
            // Fallback: flat Map-based messages
            for (Map<String, String> msg : request.messages()) {
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
        List<io.sketch.mochaagents.message.ContentBlock> blocks = new ArrayList<>();

        if (contentBlocks != null && contentBlocks.isArray()) {
            for (JsonNode block : contentBlocks) {
                String type = safeStr(block, "type");
                if ("text".equals(type)) {
                    String text = safeStr(block, "text");
                    if (!content.isEmpty()) content.append("\n");
                    content.append(text);
                    blocks.add(new io.sketch.mochaagents.message.ContentBlock.TextBlock(text));
                } else if ("tool_use".equals(type)) {
                    String id = safeStr(block, "id");
                    String name = safeStr(block, "name");
                    JsonNode inputNode = block.get("input");
                    Map<String, Object> inputMap = inputNode != null && !inputNode.isNull()
                            ? jsonNodeToMap(inputNode) : Map.of();
                    if (!content.isEmpty()) content.append("\n");
                    content.append("[tool_use: ").append(name)
                            .append("(").append(inputNode).append(")]");
                    blocks.add(new io.sketch.mochaagents.message.ContentBlock.ToolUseBlock(id, name, inputMap));
                } else if ("thinking".equals(type)) {
                    String thought = safeStr(block, "thinking");
                    String sig = safeStr(block, "signature");
                    blocks.add(new io.sketch.mochaagents.message.ContentBlock.ThinkingBlock(thought,
                            sig != null && !sig.isEmpty() ? sig : null));
                }
            }
        }

        JsonNode usage = root.get("usage");
        int inputTokens = usage != null ? safeInt(usage, "input_tokens") : 0;
        int outputTokens = usage != null ? safeInt(usage, "output_tokens") : 0;

        List<io.sketch.mochaagents.message.Message> assistantMessages = blocks.isEmpty() ? List.of()
                : List.of(new io.sketch.mochaagents.message.Message.AssistantMessage(blocks, modelId,
                        new io.sketch.mochaagents.message.Message.TokenUsage(inputTokens, outputTokens)));

        return new ResponseParseResult(content.toString(), inputTokens, outputTokens, assistantMessages);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonNodeToMap(JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        try {
            return JSON.convertValue(node, Map.class);
        } catch (Exception e) { return Map.of(); }
    }

    // ── Typed message → Anthropic JSON helpers ──

    private ObjectNode toAnthropicUserMessage(io.sketch.mochaagents.message.Message.UserMessage u) {
        ObjectNode node = JSON.createObjectNode();
        node.put("role", "user");
        if (!u.toolResults().isEmpty()) {
            ArrayNode content = JSON.createArrayNode();
            if (u.content() != null && !u.content().isEmpty())
                content.addObject().put("type", "text").put("text", u.content());
            for (var tr : u.toolResults()) {
                if (tr instanceof io.sketch.mochaagents.message.ContentBlock.ToolResultBlock tb) {
                    ObjectNode trNode = content.addObject();
                    trNode.put("type", "tool_result");
                    trNode.put("tool_use_id", tb.toolUseId());
                    trNode.put("content", tb.content());
                    if (tb.isError()) trNode.put("is_error", true);
                }
            }
            node.set("content", content);
        } else {
            node.put("content", u.content());
        }
        return node;
    }

    private ObjectNode toAnthropicAssistantMessage(io.sketch.mochaagents.message.Message.AssistantMessage a) {
        ObjectNode node = JSON.createObjectNode();
        node.put("role", "assistant");
        if (a.content().size() == 1 && a.content().get(0) instanceof io.sketch.mochaagents.message.ContentBlock.TextBlock t) {
            node.put("content", t.text());
        } else {
            ArrayNode content = JSON.createArrayNode();
            for (var b : a.content()) {
                if (b instanceof io.sketch.mochaagents.message.ContentBlock.TextBlock t)
                    content.addObject().put("type", "text").put("text", t.text());
                else if (b instanceof io.sketch.mochaagents.message.ContentBlock.ToolUseBlock tu) {
                    ObjectNode tuNode = content.addObject();
                    tuNode.put("type", "tool_use");
                    tuNode.put("id", tu.id());
                    tuNode.put("name", tu.name());
                    tuNode.set("input", JSON.valueToTree(tu.input()));
                } else if (b instanceof io.sketch.mochaagents.message.ContentBlock.ThinkingBlock th) {
                    ObjectNode thNode = content.addObject();
                    thNode.put("type", "thinking");
                    thNode.put("thinking", th.thought());
                    if (th.signature() != null) thNode.put("signature", th.signature());
                }
            }
            node.set("content", content);
        }
        return node;
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

        public AnthropicModel build() {
            if (modelId == null) modelId = "claude-sonnet-4-20250514";
            return new AnthropicModel(this);
        }
    }
}
