package io.sketch.mochaagents.memory;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 记忆接口 — 持久化记忆条目的抽象契约.
 *
 * <p>实现类可以是内存驻留（{@link MemoryEntry}）、数据库行、向量存储文档等。
 * 统一记忆类型用字符串区分: {@code "working"} / {@code "episodic"} / {@code "semantic"}.
 */
public interface Memory {

    /** 唯一标识 */
    String id();

    /** 记忆内容 */
    String content();

    /** 类型: working / episodic / semantic */
    String type();

    /** 创建时间 */
    Instant createdAt();

    /** 最后访问时间 */
    Instant lastAccessedAt();

    /** 访问计数 */
    int accessCount();

    /** 重要性评分 (0.0-1.0) */
    double importance();

    /** 关联标签 */
    Set<String> tags();

    /** 关联概念（语义记忆专用） */
    Set<String> concepts();

    /** 情景 ID（情景记忆专用） */
    Optional<String> episodeId();

    /** 扩展元数据 */
    Map<String, Object> metadata();

    /** 标记为已访问 */
    void touch();

    /** 类型常量 */
    String TYPE_WORKING = "working";
    String TYPE_EPISODIC = "episodic";
    String TYPE_SEMANTIC = "semantic";
}
