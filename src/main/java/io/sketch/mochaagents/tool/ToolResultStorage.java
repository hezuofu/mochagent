package io.sketch.mochaagents.tool;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/**
 * Tool result overflow management — when results exceed max size, save to disk
 * and return a file path instead. Pattern from claude-code's toolResultStorage.ts.
 * @author lanxia39@163.com
 */
public final class ToolResultStorage {
    private static final int DEFAULT_MAX_CHARS = 100_000;
    private final Path storageDir;
    private final int maxResultChars;

    public ToolResultStorage() { this(Paths.get(System.getProperty("java.io.tmpdir"), "mocha-results"), DEFAULT_MAX_CHARS); }
    public ToolResultStorage(Path dir, int maxChars) {
        this.storageDir = dir; this.maxResultChars = maxChars;
        try { Files.createDirectories(dir); } catch (IOException e) { /* ignore */ }
    }

    /** Store a tool result, offloading to disk if too large. Returns display string. */
    public String store(String toolName, String result) {
        if (result == null) return "(null)";
        if (result.length() <= maxResultChars) return result;

        // Offload to disk
        String fileName = toolName + "-" + System.currentTimeMillis() + ".txt";
        Path file = storageDir.resolve(fileName);
        try {
            Files.writeString(file, result);
            return "[Result stored: " + file + " (" + result.length() + " chars, preview below)]\n"
                    + result.substring(0, 1000) + "...";
        } catch (IOException e) {
            return result.substring(0, maxResultChars) + "...(truncated)";
        }
    }
}
