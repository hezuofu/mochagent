// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sketch.mochaagents.lsp.LspManager;
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * LSP tool — exposes goToDefinition, findReferences, hover, documentSymbol to agents.
 *
 * @author lanxia39@163.com
 */
public final class LspTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] OPERATIONS = {
            "goToDefinition", "findReferences", "hover", "documentSymbol",
            "goToImplementation", "prepareCallHierarchy", "incomingCalls", "outgoingCalls"
    };

    private final LspManager manager;

    public LspTool(LspManager manager) { this.manager = manager; }

    @Override public String getName() { return "lsp"; }
    @Override public String getDescription() { return "Code intelligence: go-to-definition, find references, hover, symbols."; }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }

    @Override
    public Map<String, ToolInput> getInputs() {
        Map<String, ToolInput> inputs = new LinkedHashMap<>();
        inputs.put("operation", new ToolInput("string", "Operation: " + String.join(", ", OPERATIONS), false));
        inputs.put("filePath", new ToolInput("string", "Absolute file path", false));
        inputs.put("line", new ToolInput("number", "Line number (0-based)", true));
        inputs.put("character", new ToolInput("number", "Character offset (0-based)", true));
        return inputs;
    }

    @Override
    public Object call(Map<String, Object> args) {
        String operation = (String) args.get("operation");
        String filePath = (String) args.get("filePath");
        int line = args.containsKey("line") ? ((Number) args.get("line")).intValue() : 0;
        int character = args.containsKey("character") ? ((Number) args.get("character")).intValue() : 0;

        String method = toLspMethod(operation);
        ObjectNode params = buildParams(filePath, method, line, character);

        try {
            CompletableFuture<JsonNode> req = (CompletableFuture<JsonNode>) (Object) manager.request(filePath, method, params);
            JsonNode result = req.get(30, TimeUnit.SECONDS);
            return formatResult(operation, result);
        } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
            return Map.of("error", e.getMessage());
        } catch (java.util.concurrent.TimeoutException e) {
            return Map.of("error", "LSP request timed out");
        }
    }

    // ── Private ──

    private static String toLspMethod(String op) {
        return switch (op) {
            case "goToDefinition" -> "textDocument/definition";
            case "findReferences" -> "textDocument/references";
            case "hover" -> "textDocument/hover";
            case "documentSymbol" -> "textDocument/documentSymbol";
            case "goToImplementation" -> "textDocument/implementation";
            case "prepareCallHierarchy" -> "textDocument/prepareCallHierarchy";
            case "incomingCalls" -> "callHierarchy/incomingCalls";
            case "outgoingCalls" -> "callHierarchy/outgoingCalls";
            default -> throw new IllegalArgumentException("Unknown operation: " + op);
        };
    }

    private static ObjectNode buildParams(String filePath, String method, int line, int character) {
        ObjectNode params = JSON.createObjectNode();
        ObjectNode td = JSON.createObjectNode();
        td.put("uri", "file://" + filePath);
        params.set("textDocument", td);

        if (!method.equals("textDocument/documentSymbol")) {
            ObjectNode pos = JSON.createObjectNode();
            pos.put("line", line);
            pos.put("character", character);
            params.set("position", pos);
        }

        if (method.equals("textDocument/references")) {
            ObjectNode ctx = JSON.createObjectNode();
            ctx.put("includeDeclaration", true);
            params.set("context", ctx);
        }
        return params;
    }

    private static Map<String, Object> formatResult(String operation, JsonNode result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("operation", operation);
        if (result.isArray()) {
            output.put("resultCount", result.size());
            output.put("result", result);
        } else {
            output.put("result", result);
        }
        return output;
    }

    // ── Factory ──

    public static LspTool create(LspManager manager) { return new LspTool(manager); }
}
