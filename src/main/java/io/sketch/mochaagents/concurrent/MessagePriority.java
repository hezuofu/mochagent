// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.concurrent;

/**
 * Message priority — Claude Code pattern: now > next > later.
 *
 * @author lanxia39@163.com
 */
public enum MessagePriority {
    NOW(0),    // User interrupt — process immediately
    NEXT(1),   // User input — process in next turn
    LATER(2);  // Notifications/attachments — delayed

    final int order;
    MessagePriority(int order) { this.order = order; }
}
