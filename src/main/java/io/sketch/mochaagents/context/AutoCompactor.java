package io.sketch.mochaagents.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Automatic context compaction — monitors token count, triggers compression
 * when approaching context window limit (80% threshold).
 *
 * <p>Pattern adapted from claude-code's autoCompact.ts.
 * @author lanxia39@163.com
 */
public class AutoCompactor {

    private static final Logger log = LoggerFactory.getLogger(AutoCompactor.class);
    private static final double THRESHOLD = 0.80;

    private final ContextManager ctx;
    private final int contextLimit;
    private final Set<String> recentFiles = new LinkedHashSet<>();
    private int compactionCount;
    private int consecutiveFailures;

    public AutoCompactor(ContextManager ctx, int contextLimit) {
        this.ctx = ctx;
        this.contextLimit = contextLimit;
    }

    /** Check and run compaction if needed. Returns true if compaction occurred. */
    public boolean checkAndCompact() {
        int currentTokens = ctx.tokenCount();
        int threshold = (int) (contextLimit * THRESHOLD);

        if (currentTokens < threshold) return false;

        log.info("Auto-compact: {} / {} tokens ({}%)",
                currentTokens, contextLimit, String.format("%.0f", 100.0 * currentTokens / contextLimit));

        try {
            ctx.compress();
            compactionCount++;
            consecutiveFailures = 0;
            log.info("Auto-compact done: {} tokens remaining", ctx.tokenCount());

            // Post-compact file restoration (claude-code pattern)
            restoreRecentFiles();
            return true;
        } catch (Exception e) {
            consecutiveFailures++;
            log.error("Auto-compact failed ({}/3): {}", consecutiveFailures, e.getMessage());
            return false;
        }
    }

    private void restoreRecentFiles() {
        for (String file : recentFiles) {
            try {
                String content = java.nio.file.Files.readString(java.nio.file.Paths.get(file));
                ctx.addChunk(new ContextChunk("restore-" + file.hashCode(), "system",
                        "[File: " + file + "]\n" + safePrefix(content, 5000), 100));
            } catch (Exception ignored) { /* file may be gone */ }
        }
    }

    public void trackFile(String path) {
        recentFiles.add(path);
        if (recentFiles.size() > 10) recentFiles.remove(recentFiles.iterator().next());
    }

    public int compactionCount() { return compactionCount; }
    public int consecutiveFailures() { return consecutiveFailures; }

    private static String safePrefix(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
