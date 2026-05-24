// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.memory;

import io.sketch.mochaagents.shared.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent global memory — verified facts, user preferences, environment details.
 *
 * <p>Pattern from GenericAgent's global_mem.txt + global_mem_insight.txt.
 * Only stores VERIFIED information (action-proven, not guessed).
 * Injected into system prompt every N turns to prevent context decay.
  * @author lanxia39@163.com
 */
public class GlobalMemory {

    private static final Logger log = LoggerFactory.getLogger(GlobalMemory.class);

    private final Path file;

    public GlobalMemory() {
        this(Paths.get(System.getProperty("user.home"), ".mocha", "global_memory.md"));
    }

    public GlobalMemory(Path file) {
        this.file = file;
        try { Files.createDirectories(file.getParent()); }
        catch (IOException e) { log.warn("Cannot create memory dir: {}", e.getMessage()); }
    }

    /** Read the full global memory content. */
    public String read() {
        try {
            if (Files.exists(file)) return Files.readString(file);
        } catch (IOException e) { log.debug("Cannot read global memory: {}", e.getMessage()); }
        return "";
    }

    /** Append a verified entry to global memory. */
    public void append(String entry) {
        if (entry == null || entry.isBlank()) return;
        try {
            String stamp = Instant.now().toString().substring(0, 10);
            String block = "\n### " + stamp + "\n" + entry.trim() + "\n";
            Files.writeString(file, block, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("Global memory updated: {}", Strings.truncate(entry, 80));
        } catch (IOException e) { log.warn("Cannot write global memory: {}", e.getMessage()); }
    }

    /** Compact: keep only the last N entries. */
    public void compact(int maxEntries) {
        try {
            if (!Files.exists(file)) return;
            String content = Files.readString(file);
            String[] sections = content.split("\n### ");
            if (sections.length <= maxEntries + 1) return;
            List<String> keep = new ArrayList<>();
            keep.add(sections[0]); // header
            for (int i = sections.length - maxEntries; i < sections.length; i++)
                keep.add("### " + sections[i]);
            Files.writeString(file, String.join("", keep));
        } catch (IOException e) { log.debug("Compact failed: {}", e.getMessage()); }
    }

    public Path file() { return file; }
}
