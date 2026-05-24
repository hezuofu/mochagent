// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.agent;
import io.sketch.mochaagents.MochaException;
import io.sketch.mochaagents.context.UserWorkspace;

import io.sketch.mochaagents.model.FallbackModel;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class UserWorkspaceTest {

    @Test void userIdIsPersistent() throws Exception {
        Path tmp = Files.createTempDirectory("mocha-test");
        try {
            UserWorkspace ws1 = new UserWorkspace(tmp);
            String id1 = ws1.userId();
            UserWorkspace ws2 = new UserWorkspace(tmp);
            assertEquals(id1, ws2.userId());
        } finally { deleteDir(tmp); }
    }

    @Test void fallbackLLMReturnsHelpfulMessage() {
        FallbackModel llm = new FallbackModel();
        String r = llm.complete(io.sketch.mochaagents.model.ModelRequest.builder().build()).content();
        assertTrue(r.contains("No Model configured"));
    }

    @Test void exceptionHierarchyHasErrorCodes() {
        var e = new MochaException.LlmException("test", 429, "gpt-4");
        assertTrue(e.isRateLimit());
        assertTrue(e.isRetryable());
        assertEquals("LLM_ERROR", e.errorCode());
    }

    @Test void toolExceptionCarriesContext() {
        var e = new MochaException.ToolException("rm", "permission denied");
        assertEquals("rm", e.toolName());
        assertEquals("TOOL_ERROR", e.errorCode());
    }

    private static void deleteDir(Path dir) {
        try { Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
    }
}
