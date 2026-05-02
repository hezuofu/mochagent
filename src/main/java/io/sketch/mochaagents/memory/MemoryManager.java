package io.sketch.mochaagents.memory;

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
 * </ul>
 */
public class MemoryManager {

    // ============ 评分权重 ============

    private static final double ACCESS_WEIGHT = 0.4;
    private static final double RECENCY_WEIGHT = 0.3;
    private static final double TAG_WEIGHT = 0.2;
    private static final double CONTENT_WEIGHT = 0.1;
    private static final int MAX_SUMMARY_LENGTH = 500;

    // ============ 存储 ============

    private final MemoryStore store;
    private final int maxWorkingSize;
    private final int maxEpisodicSize;

    public MemoryManager(MemoryStore store, int maxWorkingSize, int maxEpisodicSize) {
        this.store = Objects.requireNonNull(store, "MemoryStore must not be null");
        this.maxWorkingSize = maxWorkingSize;
        this.maxEpisodicSize = maxEpisodicSize;
    }

    public MemoryManager(MemoryStore store) {
        this(store, 100, 10000);
    }

    public MemoryManager() {
        this(new InMemoryMemoryStore(), 100, 10000);
    }

    /** 获取底层存储后端. */
    public MemoryStore store() {
        return store;
    }

    // ============ 委托 CRUD ============

    /** 存储一条记忆. */
    public void store(Memory memory) {
        store.store(memory);
        enforceCapacity(memory.type());
    }

    /** 按 ID 检索. */
    public Optional<Memory> get(String id) {
        return store.get(id);
    }

    /** 删除记忆. */
    public void forget(String id) {
        store.forget(id);
    }

    /** 清除指定类型. */
    public void clear(String type) {
        store.clear(type);
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
        if (Memory.TYPE_WORKING.equals(type)) {
            List<Memory> working = getByType(Memory.TYPE_WORKING);
            while (working.size() > maxWorkingSize) {
                working.stream()
                        .min(Comparator.comparingDouble(Memory::importance))
                        .ifPresent(m -> forget(m.id()));
                working = getByType(Memory.TYPE_WORKING);
            }
        } else if (Memory.TYPE_EPISODIC.equals(type)) {
            List<Memory> episodic = getByType(Memory.TYPE_EPISODIC);
            while (episodic.size() > maxEpisodicSize) {
                episodic.stream()
                        .min(Comparator.comparingDouble(Memory::importance))
                        .ifPresent(m -> forget(m.id()));
                episodic = getByType(Memory.TYPE_EPISODIC);
            }
        }
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
}
