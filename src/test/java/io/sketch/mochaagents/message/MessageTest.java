// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.message;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageTest {

    @Test void userMessageHasCorrectRole() {
        var msg = new Message.UserMessage("hello");
        assertEquals("user", msg.role());
        assertEquals("hello", msg.content());
    }

    @Test void assistantMessageFlattensText() {
        var msg = new Message.AssistantMessage(List.of(
                new ContentBlock.TextBlock("Hello world"),
                new ContentBlock.ToolUseBlock("tc1", "echo", java.util.Map.of("msg", "hi"))));
        assertTrue(msg.text().contains("Hello world"));
        assertTrue(msg.text().contains("echo"));
    }

    @Test void assistantMessageEmptyBlocks() {
        var msg = new Message.AssistantMessage(List.of());
        assertEquals("", msg.text());
    }

    @Test void systemPromptMessage() {
        var msg = new Message.SystemMessage.Prompt("system prompt");
        assertEquals("system", msg.role());
        assertEquals("system prompt", msg.content());
    }

    @Test void toolUseBlockHasIdAndInput() {
        var block = new ContentBlock.ToolUseBlock("id1", "bash", java.util.Map.of("cmd", "ls"));
        assertTrue(block instanceof ContentBlock.ToolUseBlock);
        assertEquals("bash", block.name());
        assertEquals("ls", block.input().get("cmd"));
    }

    @Test void textBlockContent() {
        assertEquals("hi", new ContentBlock.TextBlock("hi").text());
    }
}
