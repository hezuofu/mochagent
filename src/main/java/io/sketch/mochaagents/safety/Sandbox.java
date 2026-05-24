// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.safety;

/**
 * Minimal sandbox — execute untrusted code in isolation.
 */
@FunctionalInterface
public interface Sandbox {

    String exec(String code, String language);

    default io.sketch.mochaagents.tool.Tool wrap(io.sketch.mochaagents.tool.Tool tool) {
        return new SandboxedTool(tool, this);
    }

    default String backendName() { return getClass().getSimpleName(); }
}
