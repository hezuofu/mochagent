// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.event;

/** All agent lifecycle event types.
 *
 * @author lanxia39@163.com
 */
public enum EventType {
    STARTED, STEP_START, STEP_END,
    MODEL_CALL, TOOL_CALL,
    FINAL_ANSWER, COMPLETED, ERROR, COST,
    MEMORY_CHECKPOINT, MEMORY_SETTLE,
    PLUGIN_LOADED, PLUGIN_ACTIVATED
}
