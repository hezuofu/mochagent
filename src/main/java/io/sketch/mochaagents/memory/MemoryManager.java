package io.sketch.mochaagents.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * 记忆协调层 — 管理记忆生命周期、检索策略和重要性评估.
 *
 * <p>依赖 {@link MemoryStore} 进行物理存储，默认使用 {@link InMemoryMemoryStore}。
 * 可通过构造函数注入其他后端（文件、数据库、向量存储等）。
 *
 * <p>职责:
 * <ul>
 *   <li>委托基础 CRUD 和简单检索给 {@link MemoryStore}</li>
 *   <li>提供高级检索策略: 上下文检索、混合检索</li>
 *   <li>重要性评分与内容压缩</li>
 *   <li>容量控制与淘汰策略</li>
 *   <li>可选 MEMORY.md 索引管理（通过 {@link MemoryIndex}）</li>
 * </ul>
 * @author lanxia39@163.com
 */
public class MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);

    // ============ 评分权重 ============

    private static final double ACCESS_WEIGHT = 0.4;
    private static final double RECENCY_WEIGHT = 0.3;
    private static final double TAG_WEIGHT = 0.2;
    private static final double CONTENT_WEIGHT = 0.1;
    private static final int MAX_SUMMARY_LENGTH = 500;

    // ============ 存储 ============

    private final MemoryStore store;
    private final MemoryIndex memoryIndex;
    private final int maxWorkingSize;
    private final int maxEpisodicSize;

    public MemoryManager(MemoryStore store, MemoryIndex memoryIndex,
                          int maxWorkingSize, int maxEpisodicSize) {
        this.store = Objects.requireNonNull(store, "MemoryStore must not be null");
        this.memoryIndex = memoryIndex;
        this.maxWorkingSize = maxWorkingSize;
        this.maxEpisodicSize = maxEpisodicSize;
    }

    public MemoryManager(MemoryStore store, int maxWorkingSize, int maxEpisodicSize) {
        this(store, null, maxWorkingSize, maxEpisodicSize);
    }

    public MemoryManager(MemoryStore store) {
        this(store, null, 100, 10000);
    }

    public MemoryManager() {
        this(new InMemoryMemoryStore(), null, 100, 10000);
    }

    /** 获取底层存储后端. */
    public MemoryStore store() {
        return store;
    }

    /** 获取索引管理器（可能为 null）. */
    public Optional<MemoryIndex> index() {
        return Optional.ofNullable(memoryIndex);
    }

    // ============ 委托 CRUD ============

    /** 存储一条记忆. */
    public void store(Memory memory) {
        store.store(memory);
        log.debug("Memory stored: id={}, type={}", memory.id(), memory.type());
        enforceCapacity(memory.type());
    }

    /** 按 ID 检索. */
    public Optional<Memory> get(String id) {
        return store.get(id);
    }

    /** 删除记忆. */
    public void forget(String id) {
        store.forget(id);
        log.debug("Memory forgotten: id={}", id);
    }

    /** 清除指定类型. */
    public void clear(String type) {
        store.clear(type);
        log.debug("Memory cleared: type={}", type);
    }

    public int size() { return store.size(); }

    /** 全文搜索（简单包含匹配），按重要性降序. */
    public List<Memory> search(String query) {
        return store.search(query);
    }

    /** 按类型检索. */
    public List<Memory> getByType(String type) {
        return store.getByType(type);
    }

    /** 按标签检索. */
    public List<Memory> searchByTag(String tag) {
        return store.searchByTag(tag);
    }

    // ============ 高级检索 ============

    /** 上下文检索 — 标签匹配 + 内容匹配，按重要性和最近访问排序. */
    public List<Memory> retrieve(String context, List<String> contextTags, int maxResults) {
        log.debug("Memory retrieve: tags={}, maxResults={}", contextTags, maxResults);
        List<Memory> byTags = contextTags.stream()
                .flatMap(tag -> searchByTag(tag).stream())
                .distinct().toList();

        List<Memory> byContent = search(context);

        return Stream.concat(byTags.stream(), byContent.stream())
                .distinct()
                .sorted(Comparator.comparingDouble(Memory::importance).reversed()
                        .thenComparing(Comparator.comparing(Memory::lastAccessedAt).reversed()))
                .limit(maxResults)
                .toList();
    }

    /** 混合检索 — 关键词 + 语义（标签重叠）加权. */
    public List<Memory> hybridRetrieve(String query, int maxResults) {
        return hybridRetrieve(query, maxResults, 0.6, 0.4);
    }

    public List<Memory> hybridRetrieve(String query, int maxResults,
                                        double semanticWeight, double keywordWeight) {
        log.debug("Memory hybridRetrieve: query='{}', maxResults={}", query, maxResults);
        List<Memory> candidates = search(query);

        Map<Memory, Double> scored = new LinkedHashMap<>();
        for (Memory m : candidates) {
            double kw = keywordScore(m, query);
            double sem = semanticScore(m, query);
            scored.put(m, semanticWeight * sem + keywordWeight * kw);
        }

        return scored.entrySet().stream()
                .sorted(Map.Entry.<Memory, Double>comparingByValue().reversed())
                .limit(maxResults)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 最近 N 条情景记忆. */
    public List<Memory> recentEpisodes(int n) {
        return getByType(Memory.TYPE_EPISODIC).stream()
                .sorted(Comparator.comparing(Memory::createdAt).reversed())
                .limit(n)
                .toList();
    }

    // ============ 重要性评估 ============

    /** 综合评分 (0.0-1.0)，基于访问频率、时间衰减、标签、内容长度. */
    public double score(Memory memory) {
        double accessScore = Math.min(1.0, memory.accessCount() / 100.0);
        double recencyScore = recencyScore(memory.lastAccessedAt());
        double tagScore = Math.min(1.0, memory.tags().size() / 10.0);
        double lengthScore = Math.min(1.0, memory.content().length() / 1000.0);
        return ACCESS_WEIGHT * accessScore
             + RECENCY_WEIGHT * recencyScore
             + TAG_WEIGHT * tagScore
             + CONTENT_WEIGHT * lengthScore;
    }

    private static double recencyScore(Instant lastAccess) {
        long hours = Duration.between(lastAccess, Instant.now()).toHours();
        return Math.exp(-hours / 168.0);
    }

    // ============ 内容压缩 ============

    /** 超长内容截断压缩. */
    public String compressContent(String content) {
        if (content.length() <= MAX_SUMMARY_LENGTH) return content;
        return content.substring(0, MAX_SUMMARY_LENGTH) + "... [compressed]";
    }

    // ============ 内部 ============

    private void enforceCapacity(String type) {
        boolean isWorking = Memory.TYPE_WORKING.equals(type);
        int maxSize = isWorking ? maxWorkingSize : maxEpisodicSize;
        List<Memory> items = getByType(type);

        if (items.size() <= maxSize) return;

        // Sort by importance ascending, remove the lowest-N in one pass (O(n log n))
        List<Memory> toRemove = items.stream()
                .sorted(Comparator.comparingDouble(Memory::importance))
                .limit(items.size() - maxSize)
                .toList();

        for (Memory m : toRemove) {
            forget(m.id());
        }
        log.debug("Memory {} capacity enforced: removed={}/{}, kept={}",
                type, toRemove.size(), items.size(), maxSize);
    }

    private static double keywordScore(Memory m, String query) {
        String lower = m.content().toLowerCase();
        String[] words = query.toLowerCase().split("\\s+");
        long hits = Stream.of(words).filter(lower::contains).count();
        return words.length > 0 ? (double) hits / words.length : 0.0;
    }

    private static double semanticScore(Memory m, String query) {
        Set<String> qWords = new HashSet<>(List.of(query.toLowerCase().split("\\s+")));
        Set<String> tagLower = new HashSet<>();
        m.tags().forEach(t -> tagLower.add(t.toLowerCase()));
        long overlap = qWords.stream().filter(tagLower::contains).count();
        return tagLower.isEmpty() ? 0.0 : (double) overlap / Math.max(1, tagLower.size());
    }

    // ============ MEMORY.md 索引集成 ============

    /**
     * 存储记忆并同步更新 MEMORY.md 索引.
     * <p>等价于先调用 {@link #store(Memory)}，再在 MEMORY.md 中追加入口.
     * 如果没有配置 {@link MemoryIndex}，则退化为普通 store.
     *
     * @param memory   要持久化的记忆
     * @param title    记忆标题（用于索引入口）
     * @param hook     一句话描述（建议 ≤150 字符）
     * @param fileName 对应的 .md 文件名（不含路径，如 "user_role.md"）
     */
    public void storeWithIndex(Memory memory, String title, String hook, String fileName) {
        store(memory);
        if (memoryIndex != null) {
            memoryIndex.addEntry(title, fileName, hook);
        }
    }

    /**
     * 构建记忆系统 prompt — 对齐 claude-code 的 buildMemoryPrompt.
     * <p>生成包含记忆系统使用说明和 MEMORY.md 索引内容的完整 prompt。
     * 无索引时返回仅含说明的 prompt.
     *
     * @param displayName 显示名称（如 "auto memory"、"agent memory"）
     * @param memoryDir   记忆目录路径（用于 prompt 中的路径展示）
     * @return 格式化的记忆 prompt 字符串
     */
    public String loadMemoryPrompt(String displayName, String memoryDir) {
        StringBuilder sb = new StringBuilder();

        // 标题与基本说明
        sb.append("# ").append(displayName).append("\n\n");
        sb.append("You have a persistent, file-based memory system at `")
                .append(memoryDir).append("`. ")
                .append("This directory already exists — write to it directly ")
                .append("with the Write tool (do not run mkdir or check for its existence).")
                .append("\n\n");

        sb.append("You should build up this memory system over time so that future ")
                .append("conversations can have a complete picture of who the user is, ")
                .append("how they'd like to collaborate with you, what behaviors to avoid ")
                .append("or repeat, and the context behind the work the user gives you.")
                .append("\n\n");

        sb.append("If the user explicitly asks you to remember something, save it ")
                .append("immediately as whichever type fits best. If they ask you to ")
                .append("forget something, find and remove the relevant entry.")
                .append("\n\n");

        // 保存说明
        sb.append("## How to save memories\n\n");

        if (memoryIndex != null) {
            sb.append("Saving a memory is a two-step process:\n\n");
            sb.append("**Step 1** — write the memory to its own file (e.g., `user_role.md`, ")
                    .append("`feedback_testing.md`) using frontmatter format.\n\n");
            sb.append("**Step 2** — add a pointer to that file in `")
                    .append(MemoryIndex.ENTRYPOINT_NAME).append("`. `")
                    .append(MemoryIndex.ENTRYPOINT_NAME)
                    .append("` is an index, not a memory — each entry should be one line, ")
                    .append("under ~150 characters: `- [Title](file.md) — one-line hook`. ")
                    .append("It has no frontmatter. Never write memory content directly into `")
                    .append(MemoryIndex.ENTRYPOINT_NAME).append("`.\n\n");
            sb.append("- `").append(MemoryIndex.ENTRYPOINT_NAME)
                    .append("` is always loaded into your conversation context — lines after ")
                    .append(MemoryIndex.MAX_ENTRYPOINT_LINES)
                    .append(" will be truncated, so keep the index concise\n");
        } else {
            sb.append("Write each memory to its own file (e.g., `user_role.md`, ")
                    .append("`feedback_testing.md`) using frontmatter format.\n\n");
        }

        sb.append("- Keep the name, description, and type fields in memory files up-to-date ")
                .append("with the content\n");
        sb.append("- Organize memory semantically by topic, not chronologically\n");
        sb.append("- Update or remove memories that turn out to be wrong or outdated\n");
        sb.append("- Do not write duplicate memories. First check if there is an existing ")
                .append("memory you can update before writing a new one.\n\n");

        // MEMORY.md 索引内容
        sb.append("## ").append(MemoryIndex.ENTRYPOINT_NAME).append("\n\n");
        if (memoryIndex != null) {
            MemoryIndex.IndexContent ic = memoryIndex.readTruncated();
            if (!ic.content().isEmpty()) {
                sb.append(ic.content()).append("\n");
            } else {
                sb.append("Your `").append(MemoryIndex.ENTRYPOINT_NAME)
                        .append("` is currently empty. When you save new memories, ")
                        .append("they will appear here.\n");
            }
        } else {
            sb.append("Memory index (MEMORY.md) is not configured. ")
                    .append("Memories are stored directly without an index.\n");
        }

        return sb.toString();
    }

    /**
     * loadMemoryPrompt 的便捷重载，不展示具体路径.
     */
    public String loadMemoryPrompt(String displayName) {
        return loadMemoryPrompt(displayName, "<memory-dir>");
    }
}
