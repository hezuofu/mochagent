package io.sketch.mochaagents.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * {@link MemoryStore} 的 Markdown 文件持久化实现.
 *
 * <p>每条记忆存储为一个独立的 {@code .md} 文件，使用 YAML frontmatter
 * 保存元数据，正文为记忆内容。进程重启后数据不丢失。
 *
 * <pre>{@code
 * ---
 * id: abc-123
 * type: episodic
 * createdAt: 2026-05-02T10:30:00Z
 * lastAccessedAt: 2026-05-02T10:30:00Z
 * accessCount: 0
 * importance: 0.6
 * tags: [final, output]
 * concepts: []
 * episodeId: episode-1
 * ---
 *
 * Memory content here.
 * }</pre>
 */
public class MarkdownMemoryStore implements MemoryStore {

    private static final String EXT = ".md";
    private static final String FRONTMATTER_DELIM = "---";

    private final Path dir;
    private final Map<String, Memory> cache = new ConcurrentHashMap<>();

    /**
     * @param dir 存储目录，不存在则自动创建
     */
    public MarkdownMemoryStore(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create directory: " + dir, e);
        }
        loadAll();
    }

    // ============ MemoryStore 实现 ============

    @Override
    public void store(Memory memory) {
        cache.put(memory.id(), memory);
        writeFile(memory);
    }

    @Override
    public Optional<Memory> get(String id) {
        Memory m = cache.get(id);
        if (m != null) m.touch();
        return Optional.ofNullable(m);
    }

    @Override
    public void forget(String id) {
        cache.remove(id);
        try {
            Files.deleteIfExists(filePath(id));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete memory file: " + id, e);
        }
    }

    @Override
    public void clear(String type) {
        List<String> toRemove = new ArrayList<>();
        for (Memory m : cache.values()) {
            if (m.type().equals(type)) {
                toRemove.add(m.id());
            }
        }
        for (String id : toRemove) {
            forget(id);
        }
    }

    @Override
    public int size() {
        return cache.size();
    }

    @Override
    public List<Memory> search(String query) {
        String lower = query.toLowerCase();
        return sorted(cache.values().stream()
                .filter(m -> m.content().toLowerCase().contains(lower)));
    }

    @Override
    public List<Memory> getByType(String type) {
        return sorted(cache.values().stream()
                .filter(m -> m.type().equals(type)));
    }

    @Override
    public List<Memory> searchByTag(String tag) {
        return sorted(cache.values().stream()
                .filter(m -> m.tags().contains(tag)));
    }

    @Override
    public Stream<Memory> entries() {
        return cache.values().stream();
    }

    // ============ 文件 I/O ============

    private Path filePath(String id) {
        return dir.resolve(id + EXT);
    }

    private void loadAll() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + EXT)) {
            for (Path file : stream) {
                try {
                    Memory entry = parseFile(file);
                    cache.put(entry.id(), entry);
                } catch (Exception e) {
                    // 跳过损坏文件，不阻塞整体加载
                    System.err.println("[MarkdownMemoryStore] Skip corrupted file: " + file + " - " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list memory directory: " + dir, e);
        }
    }

    static MemoryEntry parseFile(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, String> fm = parseFrontmatter(raw);
        String content = extractContent(raw);

        MemoryEntry.Builder b = MemoryEntry.builder()
                .id(fm.getOrDefault("id", UUID.randomUUID().toString()))
                .content(content)
                .type(fm.getOrDefault("type", Memory.TYPE_WORKING))
                .importance(parseDouble(fm.get("importance"), 0.5))
                .tags(parseSet(fm.get("tags")))
                .concepts(parseSet(fm.get("concepts")))
                .accessCount(parseInt(fm.get("accessCount"), 0));

        String epId = fm.get("episodeId");
        if (epId != null && !epId.isEmpty()) b.episodeId(epId);

        String cat = fm.get("createdAt");
        if (cat != null && !cat.isEmpty()) b.createdAt(Instant.parse(cat));

        String lat = fm.get("lastAccessedAt");
        if (lat != null && !lat.isEmpty()) b.lastAccessedAt(Instant.parse(lat));

        return b.build();
    }

    private void writeFile(Memory memory) {
        StringBuilder sb = new StringBuilder();
        sb.append(FRONTMATTER_DELIM).append('\n');
        sb.append("id: ").append(memory.id()).append('\n');
        sb.append("type: ").append(memory.type()).append('\n');
        sb.append("createdAt: ").append(memory.createdAt()).append('\n');
        sb.append("lastAccessedAt: ").append(memory.lastAccessedAt()).append('\n');
        sb.append("accessCount: ").append(memory.accessCount()).append('\n');
        sb.append("importance: ").append(memory.importance()).append('\n');
        sb.append("tags: ").append(formatSet(memory.tags())).append('\n');
        sb.append("concepts: ").append(formatSet(memory.concepts())).append('\n');
        sb.append("episodeId: ").append(memory.episodeId().orElse("")).append('\n');
        sb.append(FRONTMATTER_DELIM).append('\n');
        sb.append('\n');
        sb.append(memory.content());
        sb.append('\n');

        try {
            Files.writeString(filePath(memory.id()), sb.toString(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write memory file: " + memory.id(), e);
        }
    }

    // ============ YAML frontmatter 解析 ============

    /**
     * 解析两个 {@code ---} 之间的简单 YAML key: value 对。
     * 支持数组值 {@code [item1, item2]}，不嵌套。
     */
    static Map<String, String> parseFrontmatter(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        String[] lines = raw.split("\\R");
        boolean inFm = false;
        for (String line : lines) {
            if (FRONTMATTER_DELIM.equals(line.trim())) {
                if (!inFm) { inFm = true; continue; }
                else break;
            }
            if (!inFm) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            map.put(key, value);
        }
        return map;
    }

    /** 提取 frontmatter 之后的内容（跳过首个 frontmatter 后的空白行）. */
    static String extractContent(String raw) {
        String[] lines = raw.split("\\R");
        StringBuilder sb = new StringBuilder();
        int delimCount = 0;
        for (String line : lines) {
            if (FRONTMATTER_DELIM.equals(line.trim())) {
                delimCount++;
                continue;
            }
            if (delimCount >= 2) {
                if (!sb.isEmpty() || !line.isBlank()) {
                    if (!sb.isEmpty()) sb.append('\n');
                    sb.append(line);
                }
            }
        }
        return sb.toString().stripTrailing();
    }

    // ============ 序列化辅助 ============

    static String formatSet(Set<String> set) {
        if (set == null || set.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String item : set) {
            if (!first) sb.append(", ");
            sb.append(item);
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    static Set<String> parseSet(String raw) {
        if (raw == null || raw.isEmpty() || "[]".equals(raw)) return Set.of();
        String inner = raw;
        if (inner.startsWith("[") && inner.endsWith("]")) {
            inner = inner.substring(1, inner.length() - 1);
        }
        if (inner.isBlank()) return Set.of();
        return Arrays.stream(inner.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static double parseDouble(String s, double fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return fallback; }
    }

    private static int parseInt(String s, int fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    private static List<Memory> sorted(Stream<Memory> stream) {
        return stream.sorted(Comparator.comparingDouble(Memory::importance).reversed()).toList();
    }
}
