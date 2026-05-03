package io.sketch.mochaagents.memory;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 物理存储抽象 — 定义记忆的持久化契约.
 *
 * <p>实现类可以是:
 * <ul>
 *   <li>{@link InMemoryMemoryStore} — 内存驻留（默认）</li>
 *   <li>{@link MarkdownMemoryStore} — Markdown 文件持久化</li>
 *   <li>DatabaseMemoryStore — JDBC/JPA</li>
 *   <li>VectorMemoryStore — 向量数据库</li>
 * </ul>
 *
 * <p>存储层只负责基础 CRUD 和简单检索，高级检索策略
 * 由 {@link MemoryManager} 在协调层实现.
 * @author lanxia39@163.com
 */
public interface MemoryStore {

    /** 存储一条记忆. */
    void store(Memory memory);

    /** 按 ID 检索. */
    Optional<Memory> get(String id);

    /** 删除记忆. */
    void forget(String id);

    /** 清除指定类型的所有记忆. */
    void clear(String type);

    /** 当前记忆总数. */
    int size();

    /** 全文搜索（简单包含匹配），按重要性降序. */
    List<Memory> search(String query);

    /** 按类型检索. */
    List<Memory> getByType(String type);

    /** 按标签检索. */
    List<Memory> searchByTag(String tag);

    /** 遍历所有记忆. */
    Stream<Memory> entries();
}
