// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects and deduplicates LSP diagnostics for attachment to agent turns.
 *
 * <p>Wire into {@link LspManager#onDiagnostics(String, java.util.function.Consumer)}
 * to receive background diagnostics from file saves.
 *
 * @author lanxia39@163.com
 */
public class LspDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(LspDiagnostics.class);
    private static final int MAX_FILES = 30;
    private static final int MAX_PER_FILE = 10;

    private final Map<String, List<Diagnostic>> pending = new ConcurrentHashMap<>();

    /** Called by LspManager when diagnostics arrive for a file. */
    public void onDiagnostics(String fileUri, JsonNode params) {
        if (params == null || !params.has("diagnostics")) return;
        String filePath = fileUri.replace("file://", "");
        List<Diagnostic> list = new ArrayList<>();
        for (JsonNode d : params.get("diagnostics")) {
            int severity = d.has("severity") ? d.get("severity").asInt() : 3;
            String message = d.get("message").asText();
            JsonNode range = d.get("range");
            int line = range.get("start").get("line").asInt();
            list.add(new Diagnostic(filePath, line, severity, message));
        }
        if (!list.isEmpty()) {
            list.sort(Comparator.comparingInt(Diagnostic::severity));
            pending.put(filePath, list.subList(0, Math.min(list.size(), MAX_PER_FILE)));
            log.debug("LSP diagnostics: {} ({})", filePath, list.size());
        }
    }

    /** Get and clear pending diagnostics. Called before each agent turn. */
    public List<Diagnostic> drain() {
        List<Diagnostic> all = new ArrayList<>();
        Iterator<Map.Entry<String, List<Diagnostic>>> it = pending.entrySet().iterator();
        int fileCount = 0;
        while (it.hasNext() && fileCount < MAX_FILES) {
            all.addAll(it.next().getValue());
            it.remove();
            fileCount++;
        }
        return all;
    }

    public String formatForPrompt(List<Diagnostic> diagnostics) {
        if (diagnostics.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n## LSP Diagnostics\n");
        for (var d : diagnostics) {
            sb.append(d.filePath()).append(":").append(d.line() + 1)
              .append(" [").append(severityLabel(d.severity())).append("] ")
              .append(d.message()).append("\n");
        }
        return sb.toString();
    }

    public record Diagnostic(String filePath, int line, int severity, String message) {
        public boolean isError() { return severity == 1; }
        public boolean isWarning() { return severity == 2; }
    }

    private static String severityLabel(int s) {
        return switch (s) { case 1 -> "ERROR"; case 2 -> "WARN"; case 4 -> "HINT"; default -> "INFO"; };
    }
}
