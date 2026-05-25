// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.interaction;

import java.util.Map;

/**
 * Tool invocation context — what the agent wants to execute.
 *
 * @param toolName  e.g. "bash", "file_write"
 * @param arguments the tool's argument map
 * @param content   key argument for content matching (e.g. command text for bash)
 * @author lanxia39@163.com
 */
public record ToolUse(String toolName, Map<String, Object> arguments, String content) {

    public ToolUse(String toolName, Map<String, Object> arguments) {
        this(toolName, arguments, extractContent(toolName, arguments));
    }

    private static String extractContent(String name, Map<String, Object> args) {
        if (args == null) return "";
        return switch (name) {
            case "bash", "powershell" -> String.valueOf(args.getOrDefault("command", ""));
            case "file_write", "file_edit" -> String.valueOf(args.getOrDefault("filePath", ""));
            default -> String.valueOf(args.getOrDefault("content", args.getOrDefault("query", "")));
        };
    }
}
