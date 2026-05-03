package io.sketch.mochaagents.memory;

import java.time.Instant;
import java.util.*;

/**
 * 统一记忆条目 — Agent 执行轨迹的持久化投影，{@link Memory} 接口的默认内存实现.
 *
 * <p>合并原 WorkingMemory / EpisodicMemory / SemanticMemory 三个类。
 * 类型用字符串区分: {@link Memory#TYPE_WORKING} /
 * {@link Memory#TYPE_EPISODIC} /
 * {@link Memory#TYPE_SEMANTIC}.
 * @author lanxia39@163.com
 */
public final class MemoryEntry implements Memory {

    private final String id;
    private final String content;
    private final String type;
    private final Instant createdAt;
    private volatile Instant lastAccessedAt;
    private volatile int accessCount;
    private volatile double importance;
    private final Set<String> tags;
    private final Set<String> concepts;
    private final String episodeId;
    private final Map<String, Object> metadata;

    private MemoryEntry(Builder builder) {
        this.id = builder.id;
        this.content = builder.content;
        this.type = builder.type;
        this.importance = clamp(builder.importance, 0.0, 1.0);
        this.tags = Set.copyOf(builder.tags);
        this.concepts = Set.copyOf(builder.concepts);
        this.episodeId = builder.episodeId;
        this.metadata = Map.copyOf(builder.metadata);
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
        this.lastAccessedAt = builder.lastAccessedAt != null ? builder.lastAccessedAt : this.createdAt;
        this.accessCount = builder.accessCount;
    }

    // ============ 访问器 ============

    public String id() { return id; }
    public String content() { return content; }
    public String type() { return type; }
    public Instant createdAt() { return createdAt; }
    public Instant lastAccessedAt() { return lastAccessedAt; }
    public int accessCount() { return accessCount; }
    public double importance() { return importance; }
    public Set<String> tags() { return tags; }
    public Set<String> concepts() { return concepts; }
    public Optional<String> episodeId() { return Optional.ofNullable(episodeId); }
    public Map<String, Object> metadata() { return metadata; }

    // ============ 可变操作 ============

    public void touch() {
        this.accessCount++;
        this.lastAccessedAt = Instant.now();
    }

    public void setImportance(double value) {
        this.importance = clamp(value, 0.0, 1.0);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    // ============ 快捷工厂 ============

    public static MemoryEntry working(String content) {
        return builder().type(Memory.TYPE_WORKING).content(content).build();
    }

    public static MemoryEntry episodic(String content, String episodeId) {
        return builder().type(Memory.TYPE_EPISODIC).content(content).episodeId(episodeId).build();
    }

    public static MemoryEntry semantic(String content, Set<String> concepts) {
        return builder().type(Memory.TYPE_SEMANTIC).content(content).concepts(concepts).build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String id = UUID.randomUUID().toString();
        private String content = "";
        private String type = TYPE_WORKING;
        private double importance = 0.5;
        private Set<String> tags = Set.of();
        private Set<String> concepts = Set.of();
        private String episodeId;
        private Map<String, Object> metadata = Map.of();
        private Instant createdAt;    // null → 取当前时间
        private Instant lastAccessedAt; // null → 同 createdAt
        private int accessCount;

        public Builder id(String v) { this.id = v; return this; }
        public Builder content(String v) { this.content = v; return this; }
        public Builder type(String v) { this.type = v; return this; }
        public Builder importance(double v) { this.importance = v; return this; }
        public Builder tags(Set<String> v) { this.tags = v; return this; }
        public Builder concepts(Set<String> v) { this.concepts = v; return this; }
        public Builder episodeId(String v) { this.episodeId = v; return this; }
        public Builder metadata(Map<String, Object> v) { this.metadata = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public Builder lastAccessedAt(Instant v) { this.lastAccessedAt = v; return this; }
        public Builder accessCount(int v) { this.accessCount = v; return this; }

        public MemoryEntry build() { return new MemoryEntry(this); }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MemoryEntry that && id.equals(that.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }
}
