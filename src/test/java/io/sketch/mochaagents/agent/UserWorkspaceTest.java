package io.sketch.mochaagents.agent;

import io.sketch.mochaagents.llm.FallbackLLM;
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
        FallbackLLM llm = new FallbackLLM();
        String r = llm.complete(io.sketch.mochaagents.llm.LLMRequest.builder().build()).content();
        assertTrue(r.contains("No LLM configured"));
    }

    @Test void exceptionHierarchyHasErrorCodes() {
        var e = new AgentException.LLMException("test", 429, "gpt-4");
        assertTrue(e.isRateLimit());
        assertTrue(e.retryable());
        assertEquals("LLM_ERROR", e.errorCode());
    }

    @Test void toolExceptionCarriesContext() {
        var e = new AgentException.ToolException("rm", "permission denied");
        assertEquals("rm", e.toolName());
        assertEquals("TOOL_ERROR", e.errorCode());
    }

    private static void deleteDir(Path dir) {
        try { Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
    }
}
