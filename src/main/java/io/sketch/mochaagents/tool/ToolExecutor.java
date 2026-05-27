// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool;

import io.sketch.mochaagents.event.EventBus;
import io.sketch.mochaagents.interaction.ApprovalBroker;
import io.sketch.mochaagents.interaction.DecisionPipeline;
import io.sketch.mochaagents.interaction.PermissionRules;
import io.sketch.mochaagents.interaction.Session;

/**
 * Tool executor — extends {@link ToolPipeline} for backward compatibility.
 *
 * <p>All execution logic lives in {@code ToolPipeline}. This class exists
 * so existing code that uses {@code new ToolExecutor(registry)} continues to work.
 *
 * @author lanxia39@163.com
 */
public class ToolExecutor extends ToolPipeline {

    public ToolExecutor(ToolRegistry registry, long timeoutMs, int maxRetries, long retryDelayMs) {
        super(registry, timeoutMs, maxRetries, retryDelayMs);
    }

    public ToolExecutor(ToolRegistry registry) {
        super(registry);
    }

    @Override public ToolExecutor withHooks(Hooks hooks) { super.withHooks(hooks); return this; }
    @Override public ToolExecutor withPermissions(PermissionRules rules) { super.withPermissions(rules); return this; }
    @Override public ToolExecutor withPermissions(PermissionRules rules, DecisionPipeline pipeline, ApprovalBroker broker) { super.withPermissions(rules, pipeline, broker); return this; }
    @Override public ToolExecutor withPipeline(DecisionPipeline p) { super.withPipeline(p); return this; }
    @Override public ToolExecutor withBroker(ApprovalBroker b) { super.withBroker(b); return this; }
    @Override public ToolExecutor withEvents(EventBus events) { super.withEvents(events); return this; }
    @Override public ToolExecutor withSession(Session session) { super.withSession(session); return this; }
    @Override public ToolExecutor withSessionId(String id) { super.withSessionId(id); return this; }
    @Override public ToolExecutor withMaxOutputChars(int n) { super.withMaxOutputChars(n); return this; }
}
