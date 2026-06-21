// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool;

import java.util.Map;

/**
 * Interceptor in the tool execution pipeline — Chain of Responsibility pattern.
 *
 * <p>{@link #before} returns null to continue the chain, or a ToolResult to short-circuit.
 * {@link #after} is called after execution for side effects (logging, normalization).
 *
 * @author lanxia39@163.com
 */
public interface ToolInterceptor {

    /** Called before execution. Return non-null ToolResult to short-circuit (deny, invalid). */
    default ToolResult before(ToolRegistry registry, String name, Map<String, Object> args) { return null; }

    /** Called after successful execution. */
    default void after(ToolRegistry registry, String name, Map<String, Object> args, ToolResult result) {}
}
