package io.sketch.mochaagents.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * CLAUDE.md loader — multi-level priority chain with directory traversal.
 * Pattern from claude-code's claudemd.ts.
 *
 * <p>Priority (lowest to highest):
 * <ol>
 *   <li>Managed: /etc/claude-code/CLAUDE.md</li>
 *   <li>User: ~/.claude/CLAUDE.md</li>
 *   <li>Project: .claude/CLAUDE.md, .claude/rules/*.md</li>
 *   <li>Local: CLAUDE.local.md</li>
 * </ol>
 * @author lanxia39@163.com
 */
public final class ClaudeMdLoader {

    private static final Logger log = LoggerFactory.getLogger(ClaudeMdLoader.class);

    private ClaudeMdLoader() {}

    /** Load all CLAUDE.md files from the default priority chain. */
    public static String load(Path cwd) {
        StringBuilder sb = new StringBuilder();

        // Layer 1: Managed (system-wide)
        loadIfExists(Paths.get("/etc/claude-code/CLAUDE.md"), sb);

        // Layer 2: User (~/.claude/CLAUDE.md)
        loadIfExists(Paths.get(System.getProperty("user.home"), ".claude/CLAUDE.md"), sb);

        // Layer 3: Project (.claude/CLAUDE.md, .claude/rules/*.md)
        for (Path dir : projectDirs(cwd)) {
            loadIfExists(dir.resolve("CLAUDE.md"), sb);
            loadRulesDir(dir.resolve("rules"), sb);
        }

        // Layer 4: Local (CLAUDE.local.md)
        loadIfExists(cwd.resolve("CLAUDE.local.md"), sb);

        return sb.toString();
    }

    /** Load a single file and append to builder. Handles @include directives. */
    private static void loadIfExists(Path file, StringBuilder sb) {
        if (!Files.exists(file)) return;
        try {
            String content = Files.readString(file);
            // Handle @include directives
            content = resolveIncludes(content, file.getParent());
            sb.append("\n<!-- ").append(file).append(" -->\n");
            sb.append(content).append("\n");
            log.debug("Loaded: {}", file);
        } catch (IOException e) { log.warn("Cannot read {}: {}", file, e.getMessage()); }
    }

    /** Load all .md files from a rules directory. */
    private static void loadRulesDir(Path rulesDir, StringBuilder sb) {
        if (!Files.isDirectory(rulesDir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rulesDir, "*.md")) {
            for (Path rule : stream) loadIfExists(rule, sb);
        } catch (IOException e) { log.warn("Cannot read rules dir: {}", e.getMessage()); }
    }

    /** Walk up from cwd to find all ancestor directories with .claude/CLAUDE.md. */
    private static List<Path> projectDirs(Path cwd) {
        List<Path> dirs = new ArrayList<>();
        Path current = cwd.toAbsolutePath();
        while (current != null) {
            Path claudeDir = current.resolve(".claude");
            if (Files.exists(claudeDir.resolve("CLAUDE.md")) || Files.isDirectory(claudeDir.resolve("rules")))
                dirs.add(0, claudeDir); // closer to root = lower priority
            Path parent = current.getParent();
            if (parent == null || parent.equals(current)) break;
            current = parent;
        }
        return dirs;
    }

    /** Resolve @include directives in content. */
    private static String resolveIncludes(String content, Path baseDir) {
        StringBuilder result = new StringBuilder();
        for (String line : content.split("\n")) {
            if (line.trim().startsWith("@include ")) {
                String includePath = line.trim().substring(9).trim();
                Path resolved = baseDir.resolve(includePath);
                loadIfExists(resolved, result);
            } else {
                result.append(line).append("\n");
            }
        }
        return result.toString();
    }
}
