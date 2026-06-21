// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent.loop;

/**
 * Agentic Loop 循环状态枚举.
 * @author lanxia39@163.com
 */
public enum LoopState {
    INIT,
    OBSERVE,
    PLAN,
    ACT,
    REFLECT,
    COMPLETE,
    ERROR,
    INTERRUPTED
}
