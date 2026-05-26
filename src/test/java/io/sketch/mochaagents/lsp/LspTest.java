// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.lsp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LspTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ── LspServerConfig ──

    @Test void serverConfigDefaults() {
        var cfg = new LspServer.LspServerConfig("ts-ls", new String[]{"--stdio"});
        assertEquals("ts-ls", cfg.command());
        assertEquals(1, cfg.args().length);
        assertEquals("--stdio", cfg.args()[0]);
        assertEquals(30, cfg.startupTimeout());
        assertEquals(5, cfg.shutdownTimeout());
        assertTrue(cfg.restartOnCrash());
        assertEquals(3, cfg.maxRestarts());
    }

    @Test void serverConfigSingleCommand() {
        var cfg = new LspServer.LspServerConfig("simple");
        assertEquals("simple", cfg.command());
        assertEquals(0, cfg.args().length);
    }

    // ── LspServer state machine ──

    @Test void serverInitialStateIsStopped() {
        var server = new LspServer("test", new LspServer.LspServerConfig("echo"));
        assertEquals(LspServer.State.STOPPED, server.state());
    }

    @Test void serverNameAndConfig() {
        var cfg = new LspServer.LspServerConfig("test-cmd");
        var server = new LspServer("myname", cfg);
        assertEquals("myname", server.name());
        assertSame(cfg, server.config());
    }

    // ── LspDiagnostics ──

    @Test void diagnosticsCollectAndDrain() {
        var diags = new LspDiagnostics();

        ObjectNode params = JSON.createObjectNode();
        ArrayNode arr = JSON.createArrayNode();
        ObjectNode d = JSON.createObjectNode();
        d.put("severity", 1);
        d.put("message", "Type error");
        ObjectNode range = JSON.createObjectNode();
        ObjectNode start = JSON.createObjectNode();
        start.put("line", 10);
        start.put("character", 0);
        ObjectNode end = JSON.createObjectNode();
        end.put("line", 10);
        end.put("character", 5);
        range.set("start", start);
        range.set("end", end);
        d.set("range", range);
        arr.add(d);
        params.set("diagnostics", arr);

        diags.onDiagnostics("file:///test/Foo.java", params);

        var drained = diags.drain();
        assertEquals(1, drained.size());
        assertEquals("Type error", drained.get(0).message());
        assertEquals(10, drained.get(0).line());
        assertTrue(drained.get(0).isError());
    }

    @Test void diagnosticsDrainClearsPending() {
        var diags = new LspDiagnostics();

        ObjectNode params = JSON.createObjectNode();
        ArrayNode arr = JSON.createArrayNode();
        ObjectNode d = JSON.createObjectNode();
        d.put("severity", 2);
        d.put("message", "Warning");
        ObjectNode range = JSON.createObjectNode();
        ObjectNode start = JSON.createObjectNode();
        start.put("line", 0);
        start.put("character", 0);
        ObjectNode end = JSON.createObjectNode();
        end.put("line", 0);
        end.put("character", 1);
        range.set("start", start);
        range.set("end", end);
        d.set("range", range);
        arr.add(d);
        params.set("diagnostics", arr);

        diags.onDiagnostics("file:///test/Bar.java", params);
        assertEquals(1, diags.drain().size());
        assertEquals(0, diags.drain().size()); // drained
    }

    @Test void diagnosticsNullParamsIgnored() {
        var diags = new LspDiagnostics();
        assertDoesNotThrow(() -> diags.onDiagnostics("test", null));
        assertTrue(diags.drain().isEmpty());
    }

    @Test void diagnosticsSeveritySorting() {
        var diags = new LspDiagnostics();

        ObjectNode params = JSON.createObjectNode();
        ArrayNode arr = JSON.createArrayNode();

        // Severity 1 (error)
        ObjectNode e = JSON.createObjectNode();
        e.put("severity", 1);
        e.put("message", "Error");
        e.set("range", minimalRange());
        arr.add(e);

        // Severity 2 (warning)
        ObjectNode w = JSON.createObjectNode();
        w.put("severity", 2);
        w.put("message", "Warning");
        w.set("range", minimalRange());
        arr.add(w);

        params.set("diagnostics", arr);

        diags.onDiagnostics("file:///test/Baz.java", params);
        var drained = diags.drain();
        assertEquals(2, drained.size());
        assertEquals(1, drained.get(0).severity()); // error first
    }

    @Test void diagnosticsFormatForPrompt() {
        var diags = new LspDiagnostics();
        var diagnostic = new LspDiagnostics.Diagnostic("/test/Foo.java", 5, 1, "Null pointer");
        var formatted = diags.formatForPrompt(java.util.List.of(diagnostic));
        assertTrue(formatted.contains("LSP Diagnostics"));
        assertTrue(formatted.contains("Foo.java"));
        assertTrue(formatted.contains("ERROR"));
        assertTrue(formatted.contains("Null pointer"));
    }

    @Test void diagnosticRecordProperties() {
        var d = new LspDiagnostics.Diagnostic("/a/b.java", 3, 2, "Unused var");
        assertEquals("/a/b.java", d.filePath());
        assertEquals(3, d.line());
        assertEquals(2, d.severity());
        assertTrue(d.isWarning());
        assertFalse(d.isError());
    }

    // ── LspDiagnosticBridge ──

    @Test void bridgeInstallAndRetrieve() {
        var mgr = new LspManager();
        var diags = new LspDiagnostics();
        LspDiagnosticBridge.install(mgr, diags);

        assertSame(mgr, LspDiagnosticBridge.manager());
        assertSame(diags, LspDiagnosticBridge.diagnostics());
    }

    // ── LspManager extension routing ──

    @Test void managerReturnsNullForUnknownExtension() {
        var mgr = new LspManager();
        assertNull(mgr.getServerForFile("test.xyz"));
    }

    @Test void languageIdMapping() {
        // Extension-to-languageId tested via manager registration
        var mgr = new LspManager();
        mgr.register("ts", ".ts", new LspServer.LspServerConfig("ts-ls"));
        assertNotNull(mgr.getServerForFile("src/app.ts"));
    }

    // ── LspException ──

    @Test void lspExceptionMessage() {
        var ex = new LspClient.LspException("Something went wrong");
        assertEquals("Something went wrong", ex.getMessage());
        assertTrue(ex instanceof RuntimeException);
    }

    // ── helpers ──

    private static ObjectNode minimalRange() {
        ObjectNode range = JSON.createObjectNode();
        ObjectNode start = JSON.createObjectNode();
        start.put("line", 0);
        start.put("character", 0);
        ObjectNode end = JSON.createObjectNode();
        end.put("line", 0);
        end.put("character", 1);
        range.set("start", start);
        range.set("end", end);
        return range;
    }
}
