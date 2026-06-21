// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool;

import java.util.List;
import java.util.Map;

/**
 * Tool execution strategy — pluggable execution model.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@code ToolPipeline} — interceptor chain, default strategy</li>
 *   <li>{@code StreamingToolExecutor} — stream-as-they-arrive (Claude Code pattern)</li>
 * </ul>
 *
 * @author lanxia39@163.com
 */
@FunctionalInterface
public interface ToolExecutionStrategy {

    ToolResult execute(String toolName, Map<String, Object> arguments);

    default List<ToolResult> executeBatch(List<ToolCall> calls) {
        return calls.stream().map(c -> execute(c.name(), c.arguments())).toList();
    }

    record ToolCall(String name, Map<String, Object> arguments) {}
}
