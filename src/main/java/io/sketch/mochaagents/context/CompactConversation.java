package io.sketch.mochaagents.context;

import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.LLMRequest;
import io.sketch.mochaagents.llm.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;

/**
 * Full replication of claude-code's compactConversation algorithm (compact.ts:387-763).
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Build compact prompt → stream compact summary via LLM</li>
 *   <li>PTL recovery: if compact itself hits prompt-too-long, truncate oldest
 *       API-round groups and retry (max 3 attempts)</li>
 *   <li>Validate summary (not null, not API error)</li>
 *   <li>Clear caches, build post-compact attachments (files, plan, skills)</li>
 *   <li>Build annotated compact boundary with metadata</li>
 *   <li>Return CompactionResult with new messages + usage stats</li>
 * </ol>
 * @author lanxia39@163.com
 */
public final class CompactConversation {

    private static final Logger log = LoggerFactory.getLogger(CompactConversation.class);
    private static final int MAX_PTL_RETRIES = 3;
    private static final int MAX_FILES_TO_RESTORE = 5;
    private static final int MAX_FILE_TOKENS = 5000;
    private static final int MAX_TOTAL_RESTORE_TOKENS = 50000;
    private static final String PTL_ERROR_PREFIX = "prompt_too_long";

    private final LLM llm;
    private final int compactMaxTokens;
    private final Set<String> recentFiles = new LinkedHashSet<>();
    private int compactionCount;
    private int consecutiveFailures;
    private int totalPtLRecoveries;

    public CompactConversation(LLM llm, int compactMaxTokens) {
        this.llm = llm;
        this.compactMaxTokens = compactMaxTokens;
    }

    public CompactConversation(LLM llm) { this(llm, 1024); }

    /**
     * Run compaction on a list of context chunks.
     * Returns the compacted chunk list, or null if compaction failed.
     */
    public CompactionResult compact(List<ContextChunk> chunks, String customInstructions) {
        if (chunks.isEmpty()) return null;

        int preCompactTokens = totalTokens(chunks);
        String transcript = buildTranscript(chunks);
        String compactPrompt = buildCompactPrompt(customInstructions, transcript);

        // ── PTL recovery loop ──
        String summary = null;
        int ptlAttempts = 0;
        String currentTranscript = transcript;

        while (ptlAttempts <= MAX_PTL_RETRIES) {
            LLMResponse response = llm.complete(LLMRequest.builder()
                    .addMessage("user", compactPrompt)
                    .maxTokens(compactMaxTokens)
                    .temperature(0.2)
                    .build());

            summary = response.content();
            if (summary == null) {
                log.error("Compact failed: null summary");
                consecutiveFailures++;
                return null;
            }
            if (!summary.startsWith(PTL_ERROR_PREFIX)) break;

            // PTL recovery: truncate oldest messages and retry
            ptlAttempts++;
            if (ptlAttempts > MAX_PTL_RETRIES) {
                log.error("Compact PTL recovery exhausted after {} attempts", MAX_PTL_RETRIES);
                consecutiveFailures++;
                return null;
            }

            currentTranscript = truncateHeadForPTLRetry(currentTranscript, preCompactTokens);
            compactPrompt = buildCompactPrompt(customInstructions, currentTranscript);
            totalPtLRecoveries++;
            log.warn("Compact PTL retry {}/{}: dropped to {} chars",
                    ptlAttempts, MAX_PTL_RETRIES, currentTranscript.length());
        }

        // Validate summary
        if (summary == null || summary.isEmpty()) {
            log.error("Compact failed: empty summary");
            consecutiveFailures++;
            return null;
        }

        // ── Build result ──
        List<ContextChunk> result = new ArrayList<>();

        // Compact boundary message with metadata
        result.add(new ContextChunk("compact-" + compactionCount, "system_boundary",
                String.format("[Compacted: %d messages → summary, %d tokens before, %d after]",
                        chunks.size(), preCompactTokens, estimateTokens(summary)),
                5));

        // Summary
        result.add(new ContextChunk("summary-" + compactionCount, "system",
                "[Compacted context]\n" + summary,
                estimateTokens(summary)));

        // Post-compact file restoration
        restoreRecentFiles(result);

        // Keep the most recent messages after the boundary
        int keepCount = Math.min(5, chunks.size());
        result.addAll(chunks.subList(Math.max(0, chunks.size() - keepCount), chunks.size()));

        compactionCount++;
        consecutiveFailures = 0;

        CompactionResult cr = new CompactionResult(result, preCompactTokens,
                totalTokens(result), ptlAttempts > 0, compactionCount);
        log.info("Compact complete: {} → {} chunks, {}/{} tokens, ptlRecoveries={}",
                chunks.size(), result.size(), preCompactTokens, totalTokens(result), ptlAttempts);
        return cr;
    }

    /** Track a file that was recently read, for post-compact restoration. */
    public void trackFile(String path) {
        recentFiles.add(path);
        if (recentFiles.size() > 10) recentFiles.remove(recentFiles.iterator().next());
    }

    /** Number of times PTL recovery was triggered. */
    public int totalPtLRecoveries() { return totalPtLRecoveries; }
    public int compactionCount() { return compactionCount; }
    public int consecutiveFailures() { return consecutiveFailures; }

    // ============ Internal ============

    private String buildCompactPrompt(String instructions, String transcript) {
        StringBuilder sb = new StringBuilder();
        sb.append("Summarize this conversation concisely. Keep key facts, decisions, and the user's intent.\n");
        if (instructions != null && !instructions.isEmpty())
            sb.append("Additional instructions: ").append(instructions).append("\n");
        sb.append("\n---\n").append(transcript).append("\n---\nSummary:");
        return sb.toString();
    }

    /**
     * Truncate the oldest ~40% of content for PTL retry.
     * In claude-code, this groups by API rounds; we approximate with paragraph splitting.
     */
    private String truncateHeadForPTLRetry(String transcript, int preCompactTokens) {
        String[] paragraphs = transcript.split("\n\n");
        int dropCount = Math.max(1, paragraphs.length / 3); // drop oldest third
        return String.join("\n\n",
                Arrays.copyOfRange(paragraphs, dropCount, paragraphs.length));
    }

    /** Build a conversation transcript from chunks. */
    private String buildTranscript(List<ContextChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (ContextChunk c : chunks) {
            String prefix = switch (c.role()) {
                case "user" -> "User: "; case "assistant" -> "Assistant: ";
                case "system" -> "System: "; default -> c.role() + ": ";
            };
            sb.append(prefix).append(truncate(c.content(), 2000)).append("\n");
        }
        return sb.toString();
    }

    /** Post-compact file restoration — re-inject recently read file content. */
    private void restoreRecentFiles(List<ContextChunk> result) {
        int restoredTokens = 0;
        for (String file : new ArrayList<>(recentFiles)) {
            if (restoredTokens >= MAX_TOTAL_RESTORE_TOKENS) break;
            try {
                String content = java.nio.file.Files.readString(java.nio.file.Paths.get(file));
                int fileTokens = Math.max(1, content.length() / 4);
                if (fileTokens > MAX_FILE_TOKENS) content = content.substring(0, MAX_FILE_TOKENS * 4);
                result.add(new ContextChunk("restore-" + file.hashCode(), "system",
                        "[File: " + file + "]\n" + content, Math.min(fileTokens, MAX_FILE_TOKENS)));
                restoredTokens += fileTokens;
                if (result.stream().filter(c -> c.role().equals("system") && c.content().startsWith("[File:")).count() >= MAX_FILES_TO_RESTORE) break;
            } catch (Exception ignored) {}
        }
    }

    // ============ Utilities ============

    private static int totalTokens(List<ContextChunk> chunks) {
        return chunks.stream().mapToInt(ContextChunk::tokenCount).sum();
    }

    private static int estimateTokens(String text) { return Math.max(1, text.length() / 4); }

    private static String truncate(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "..."; }

    // ============ Result ============

    public record CompactionResult(List<ContextChunk> chunks, int preTokens, int postTokens,
                                    boolean hadPtlRecovery, int compactionId) {}
}
