// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.lsp;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Global bridge — used by FileWrite/FileEdit tools to notify LSP of changes.
 *
 * @author lanxia39@163.com
 */
public final class LspDiagnosticBridge {

    private static final AtomicReference<LspManager> MANAGER = new AtomicReference<>();
    private static final AtomicReference<LspDiagnostics> DIAGNOSTICS = new AtomicReference<>();

    public static void install(LspManager manager, LspDiagnostics diagnostics) {
        MANAGER.set(manager);
        DIAGNOSTICS.set(diagnostics);
    }

    public static void didChange(String filePath, String newContent) {
        LspManager m = MANAGER.get();
        if (m != null) {
            m.didChange(filePath, newContent);
        }
    }

    public static void didSave(String filePath) {
        LspManager m = MANAGER.get();
        if (m != null) {
            m.didSave(filePath);
        }
    }

    public static LspDiagnostics diagnostics() { return DIAGNOSTICS.get(); }
    public static LspManager manager() { return MANAGER.get(); }
}
