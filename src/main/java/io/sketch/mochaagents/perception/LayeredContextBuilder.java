package io.sketch.mochaagents.perception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Builds the 3-layer context following claude-code's context assembly pattern:
 *
 * <pre>
 *   Layer 1: SYSTEM PROMPT (static + dynamic, separated by DYNAMIC_BOUNDARY)
 *   Layer 2: SYSTEM CONTEXT (git status, platform, model info — memoized per session)
 *   Layer 3: USER CONTEXT (CLAUDE.md files, current date — memoized per session)
 * </pre>
 *
 * <p>Memoization preserves Anthropic prompt cache across turns. The static prefix
 * is global-cacheable; the dynamic suffix and system/user contexts are session-scoped.
 *
 * @author lanxia39@163.com
 */
public class LayeredContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(LayeredContextBuilder.class);

    public static final String DYNAMIC_BOUNDARY = "--- DYNAMIC ---";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Path projectRoot;
    private final String platform;
    private final String modelName;
    private final List<Path> claudeMdPaths;

    // Memoized per session — computed once, reused across turns
    private volatile String cachedSystemContext;
    private volatile String cachedUserContext;
    private ZonedDateTime sessionStartDate;

    public LayeredContextBuilder(Path projectRoot) {
        this(projectRoot, System.getProperty("os.name"), "default", List.of());
    }

    public LayeredContextBuilder(Path projectRoot, String platform, String modelName,
                                  List<Path> claudeMdPaths) {
        this.projectRoot = projectRoot;
        this.platform = platform;
        this.modelName = modelName;
        this.claudeMdPaths = claudeMdPaths;
    }

    /** Build the complete 3-layer prompt. */
    public String buildFullContext(String staticPrompt, String dynamicPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append(staticPrompt).append("\n\n");
        sb.append(DYNAMIC_BOUNDARY).append("\n");
        sb.append(dynamicPrompt).append("\n\n");
        sb.append(getSystemContext()).append("\n");
        return sb.toString();
    }

    /** Build user context for injection as a {@code <system-reminder>} block. */
    public String buildUserContext() {
        return "<system-reminder>\n"
                + getUserContext()
                + "\n</system-reminder>";
    }

    // ============ System Context (Layer 2 — memoized) ============

    /**
     * System context: git status, platform, model info.
     * Memoized — computed once per session to preserve prompt cache.
     */
    public String getSystemContext() {
        if (cachedSystemContext != null) return cachedSystemContext;

        StringBuilder sb = new StringBuilder();
        sb.append("[System Context]\n");

        // Git status
        try {
            GitSnapshot git = captureGitState();
            sb.append("gitBranch: ").append(git.branch).append("\n");
            sb.append("gitStatus: ").append(git.status).append("\n");
            sb.append("recentCommits:\n");
            for (String c : git.recentCommits) {
                sb.append("  ").append(c).append("\n");
            }
        } catch (Exception e) {
            sb.append("gitStatus: unavailable\n");
        }

        sb.append("platform: ").append(platform).append("\n");
        sb.append("model: ").append(modelName).append("\n");

        cachedSystemContext = sb.toString();
        return cachedSystemContext;
    }

    // ============ User Context (Layer 3 — memoized) ============

    /**
     * User context: CLAUDE.md content + current date.
     * Memoized — date is snapshotted at session start.
     */
    public String getUserContext() {
        if (cachedUserContext != null) return cachedUserContext;

        if (sessionStartDate == null) {
            sessionStartDate = ZonedDateTime.now();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("date: ").append(sessionStartDate.format(DATE_FMT)).append("\n\n");

        // CLAUDE.md files
        for (Path mdPath : claudeMdPaths) {
            try {
                if (Files.exists(mdPath)) {
                    String content = Files.readString(mdPath);
                    sb.append("--- ").append(mdPath.getFileName()).append(" ---\n");
                    sb.append(truncate(content, 5000)).append("\n\n");
                }
            } catch (Exception e) {
                log.debug("Cannot read CLAUDE.md at {}: {}", mdPath, e.getMessage());
            }
        }

        cachedUserContext = sb.toString();
        return cachedUserContext;
    }

    /** Invalidate cached contexts (e.g., after git branch change or midnight crossing). */
    public void invalidateCache() {
        cachedSystemContext = null;
        cachedUserContext = null;
        log.debug("LayeredContextBuilder cache invalidated");
    }

    // ============ Git snapshot ============

    private GitSnapshot captureGitState() {
        try {
            Path gitDir = findGitDir(projectRoot);
            if (gitDir == null) return new GitSnapshot("unknown", "no git repo", List.of());

            String branch = readGitFile(gitDir, "HEAD").replace("ref: refs/heads/", "").trim();
            String status = "(clean)"; // simplified — would run `git status --porcelain`

            // Read recent commits from logs/HEAD
            List<String> commits = new ArrayList<>();
            Path headLog = gitDir.resolve("logs").resolve("HEAD");
            if (Files.exists(headLog)) {
                List<String> lines = Files.readAllLines(headLog);
                int start = Math.max(0, lines.size() - 5);
                for (int i = start; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.length() > 7) {
                        commits.add(line.substring(0, 7) + " " + extractMessage(line));
                    }
                }
            }

            return new GitSnapshot(branch, status, commits);
        } catch (Exception e) {
            return new GitSnapshot("unknown", "error: " + e.getMessage(), List.of());
        }
    }

    private static Path findGitDir(Path start) {
        Path current = start.toAbsolutePath();
        while (current != null) {
            Path gitDir = current.resolve(".git");
            if (Files.isDirectory(gitDir)) return gitDir;
            current = current.getParent();
        }
        return null;
    }

    private static String readGitFile(Path gitDir, String relativePath) {
        try {
            Path file = gitDir.resolve(relativePath);
            return Files.exists(file) ? Files.readString(file) : "";
        } catch (Exception e) { return ""; }
    }

    private static String extractMessage(String logLine) {
        // git log format: hash ... hash author ... message
        int tabIdx = logLine.lastIndexOf('\t');
        return tabIdx >= 0 ? truncate(logLine.substring(tabIdx + 1), 80) : "";
    }

    // ============ Types ============

    record GitSnapshot(String branch, String status, List<String> recentCommits) {}

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
