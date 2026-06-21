// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.context.compaction;

/**
 * Compaction levels — Claude Code pattern: snip → microCompact → collapse → autoCompact.
 *
 * @author lanxia39@163.com
 */
public enum CompactionLevel {
    /** Remove old tool results (lightweight, no LLM call). */
    SNIP,
    /** Clear single tool result by tool_use_id. */
    MICRO,
    /** Fold long tool outputs to summaries. */
    COLLAPSE,
    /** Summarize conversation history via LLM. */
    AUTO;

    public boolean needsModel() { return this == AUTO; }
}
