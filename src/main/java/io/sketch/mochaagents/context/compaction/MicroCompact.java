// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.context.compaction;

import io.sketch.mochaagents.message.Message;
import io.sketch.mochaagents.message.ContentBlock;

import java.util.*;

/**
 * Lightweight compaction — clears old tool results to placeholders in-memory
 * before API calls, without altering the persistent transcript.
 *
 * <p>Claude Code pattern (services/compact/microCompact.ts):
 * consecutive {@code tool_result} blocks whose content exceeds a threshold are
 * replaced with a short placeholder string, saving tokens without breaking the
 * {@code tool_use}/{@code tool_result} structural pairing the API requires.
 *
 * @author lanxia39@163.com
 */
public final class MicroCompact {

    public static final String CLEARED_MESSAGE = "[Old tool result content cleared]";
    private static final int MIN_CHARS_TO_CLEAR = 500;

    private static final Set<String> COMPACTABLE_TOOLS = Set.of(
        "read_file", "bash", "powershell", "grep", "glob",
        "web_search", "web_fetch", "edit_file", "write_file"
    );

    /**
     * Build a compacted copy of the message list — old tool results are
     * replaced with {@link #CLEARED_MESSAGE}. Current-round results (the
     * most recent assistant→tool sequence) are always preserved.
     *
     * @param messages  the original messages (not modified)
     * @param toolNames optional tool-name lookup (tool_use_id → tool_name)
     * @return a compacted copy + the set of cleared tool_use IDs
     */
    public static CompactResult compact(List<Message> messages,
                                         Map<String, String> toolNames) {
        if (messages == null || messages.isEmpty()) {
            return new CompactResult(List.copyOf(messages), Set.of());
        }

        Set<String> lastRoundIds = collectLastRoundToolIds(messages);
        Set<String> compactedIds = new HashSet<>();
        List<Message> result = new ArrayList<>(messages.size());

        for (Message msg : messages) {
            if (msg instanceof Message.UserMessage um && !um.toolResults().isEmpty()) {
                result.add(compactUserMessage(um, lastRoundIds, toolNames, compactedIds));
            } else {
                result.add(msg);
            }
        }

        return new CompactResult(Collections.unmodifiableList(result), Collections.unmodifiableSet(compactedIds));
    }

    private static Set<String> collectLastRoundToolIds(List<Message> messages) {
        Set<String> ids = new HashSet<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.AssistantMessage am) {
                boolean found = false;
                for (ContentBlock b : am.content()) {
                    if (b instanceof ContentBlock.ToolUseBlock tu) {
                        ids.add(tu.id());
                        found = true;
                    }
                }
                // only the most recent assistant with tools
                if (found) {
                    break;
                }
            }
        }
        return ids;
    }

    private static Message.UserMessage compactUserMessage(
            Message.UserMessage original,
            Set<String> lastRoundIds,
            Map<String, String> toolNames,
            Set<String> compactedIds) {

        List<ContentBlock> compacted = new ArrayList<>(original.toolResults().size());
        boolean anyCleared = false;

        for (ContentBlock tb : original.toolResults()) {
            if (tb instanceof ContentBlock.ToolResultBlock tr) {
                // Never clear current round results
                if (lastRoundIds.contains(tr.toolUseId())) {
                    compacted.add(tr);
                    continue;
                }
                if (shouldClear(tr)) {
                    compacted.add(new ContentBlock.ToolResultBlock(tr.toolUseId(), tr.name(), CLEARED_MESSAGE, false));
                    compactedIds.add(tr.toolUseId());
                    anyCleared = true;
                } else {
                    compacted.add(tr);
                }
            } else {
                compacted.add(tb);
            }
        }

        if (!anyCleared) {
            return original;
        }

        // Build new UserMessage with compacted tool results
        // The text content stays as-is (or summarize if all cleared)
        String content = original.content();
        if (content != null && content.length() > MIN_CHARS_TO_CLEAR && allCleared(compacted)) {
            content = CLEARED_MESSAGE;
        }
        return new Message.UserMessage(content, compacted);
    }

    private static boolean shouldClear(ContentBlock.ToolResultBlock tr) {
        String content = tr.content();
        return content != null && content.length() > MIN_CHARS_TO_CLEAR;
    }

    private static boolean allCleared(List<ContentBlock> blocks) {
        return blocks.stream().allMatch(b ->
            b instanceof ContentBlock.ToolResultBlock tr
                && CLEARED_MESSAGE.equals(tr.content()));
    }

    public record CompactResult(List<Message> messages, Set<String> compactedIds) {
        public int clearedCount() { return compactedIds.size(); }
    }
}
