// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool.internal;

import io.sketch.mochaagents.agent.MochaAgent;
import io.sketch.mochaagents.model.Model;
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import io.sketch.mochaagents.tool.ToolRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Delegate sub-task to a child agent — hermes-agent delegate_task pattern.
 *
 * @author lanxia39@163.com
 */
public final class DelegateTaskTool implements Tool {

    private final Model model;
    private final ToolRegistry toolRegistry;
    private final Map<String, MochaAgent> pool = new ConcurrentHashMap<>();
    private volatile int maxConcurrent = 3;

    public DelegateTaskTool(Model model, ToolRegistry toolRegistry) {
        this.model = model; this.toolRegistry = toolRegistry;
    }

    @Override public String getName() { return "delegate_task"; }
    @Override public String getDescription() { return "Delegate a sub-task to a child agent."; }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }

    @Override
    public Map<String, ToolInput> getInputs() {
        return Map.of("prompt", ToolInput.string("Task for child agent"));
    }

    @Override
    public Object call(Map<String, Object> args) {
        String prompt = (String) args.get("prompt");
        if (pool.size() >= maxConcurrent) return Map.of("error", "max concurrent: " + maxConcurrent);

        MochaAgent child = MochaAgent.builder("child", model)
                .toolRegistry(toolRegistry).maxSteps(16).build();

        pool.put("child-" + pool.size(), child);
        try {
            String result = child.run(prompt);
            return Map.of("result", result);
        } finally {
            pool.values().remove(child);
        }
    }

    public int activeChildren() { return pool.size(); }
}
