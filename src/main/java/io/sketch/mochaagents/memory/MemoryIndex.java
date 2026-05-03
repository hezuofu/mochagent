package io.sketch.mochaagents.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MEMORY.md 索引文件管理器 — 对齐 claude-code 的 memdir 模式.
 *
 * <p><strong>MEMORY.md is not a memory itself — it's an index.</strong>
 * Each entry is one line under ~150 chars:
 * <code>- [Title](file.md) — one-line hook</code>.
 * No frontmatter. Never write memory content directly into MEMORY.md.
 *
 * <p>Limits (aligned with claude-code):
 * <ul>
 *   <li>{@link #MAX_ENTRYPOINT_LINES} = 200</li>
 *   <li>{@link #MAX_ENTRYPOINT_BYTES} = 25,000</li>
 * </ul>
 *
 * <p>Usage example:
 * <pre>{@code
 * MemoryIndex index = new MemoryIndex(memoryDir);
 * index.addEntry("User Role", "user_role.md", "Backend developer using Java");
 * IndexContent content = index.readTruncated();
 * }</pre>
 *
 * @see MarkdownMemoryStore 用于持久化单个记忆文件
 * @author lanxia39@163.com
 */
public class MemoryIndex {

    public static final String ENTRYPOINT_NAME = "MEMORY.md";
    public static final int MAX_ENTRYPOINT_LINES = 200;
    public static final int MAX_ENTRYPOINT_BYTES = 25_000;

    private final Path filePath;

    /**
     * @param dir 记忆存储目录，MEMORY.md 将位于此目录下
     */
    public MemoryIndex(Path dir) {
        this.filePath = dir.resolve(ENTRYPOINT_NAME);
    }

    /**
     * 读取完整的 MEMORY.md 内容，不存在则返回空字符串.
     */
    public String read() {
        try {
            if (Files.exists(filePath)) {
                return Files.readString(filePath, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + ENTRYPOINT_NAME + " at " + filePath, e);
        }
        return "";
    }

    /**
     * 将内容写入 MEMORY.md，必要时自动创建父目录.
     */
    public void write(String content) {
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + ENTRYPOINT_NAME + " at " + filePath, e);
        }
    }

    /**
     * 向索引追加一条入口.
     * 格式: {@code - [Title](file.md) — one-line hook}
     *
     * @param title    记忆标题
     * @param fileName 对应的 .md 文件名
     * @param hook     一句话描述（建议 ≤150 字符）
     */
    public void addEntry(String title, String fileName, String hook) {
        String entry = "- [" + title + "](" + fileName + ") — " + hook;
        String existing = read();
        String newContent;
        if (existing.isBlank()) {
            newContent = entry + "\n";
        } else {
            newContent = existing.stripTrailing() + "\n" + entry + "\n";
        }
        write(newContent);
    }

    /**
     * 删除索引中引用指定文件名的入口.
     *
     * @param fileName 要移除的文件名（如 "user_role.md"）
     */
    public void removeEntry(String fileName) {
        String existing = read();
        if (existing.isBlank()) return;

        String[] lines = existing.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (!line.contains("(" + fileName + ")")) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(line);
            }
        }
        String newContent = sb.toString().stripTrailing();
        if (!newContent.isEmpty()) newContent += "\n";
        write(newContent);
    }

    // ==================== 截断 ====================

    /**
     * 读取并截断 MEMORY.md 内容（如果超过行数/字节限制）.
     * <p>先按行截断，再按字节截断，在最后一个完整行边界处裁剪.
     */
    public IndexContent readTruncated() {
        return truncate(read());
    }

    /**
     * 将原始内容截断到行数和字节数限制内，附加警告说明.
     * <p>先按行截断，再按字节截断（在 \n 边界处裁剪，避免截断到行中间）.
     *
     * @param raw 原始 MEMORY.md 内容
     * @return 截断后的 {@link IndexContent}
     */
    public static IndexContent truncate(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return new IndexContent("", 0, 0, false, false);
        }

        String[] contentLines = trimmed.split("\\R", -1);
        int lineCount = contentLines.length;
        int byteCount = trimmed.length();

        boolean wasLineTruncated = lineCount > MAX_ENTRYPOINT_LINES;
        boolean wasByteTruncated = byteCount > MAX_ENTRYPOINT_BYTES;

        if (!wasLineTruncated && !wasByteTruncated) {
            return new IndexContent(trimmed, lineCount, byteCount, false, false);
        }

        // 先按行截断
        String truncated = wasLineTruncated
                ? String.join("\n",
                        java.util.Arrays.copyOf(contentLines, MAX_ENTRYPOINT_LINES))
                : trimmed;

        // 再按字节截断（在最后一个完整行边界）
        if (truncated.length() > MAX_ENTRYPOINT_BYTES) {
            int cutAt = truncated.lastIndexOf('\n', MAX_ENTRYPOINT_BYTES);
            truncated = truncated.substring(0, cutAt > 0 ? cutAt : MAX_ENTRYPOINT_BYTES);
        }

        // 构建警告
        String reason;
        if (wasByteTruncated && !wasLineTruncated) {
            reason = byteCount + " bytes (limit: " + MAX_ENTRYPOINT_BYTES
                    + ") — index entries are too long";
        } else if (wasLineTruncated && !wasByteTruncated) {
            reason = lineCount + " lines (limit: " + MAX_ENTRYPOINT_LINES + ")";
        } else {
            reason = lineCount + " lines and " + byteCount + " bytes";
        }

        truncated = truncated + "\n\n> WARNING: " + ENTRYPOINT_NAME + " is " + reason
                + ". Only part of it was loaded. Keep index entries to one line "
                + "under ~200 chars; move detail into topic files.";

        return new IndexContent(truncated, lineCount, byteCount,
                wasLineTruncated, wasByteTruncated);
    }

    /**
     * 截断后的索引内容及其元数据.
     *
     * @param content          截断后的文本内容（可能包含警告说明）
     * @param lineCount        原始总行数
     * @param byteCount        原始总字节数
     * @param wasLineTruncated 是否因行数超限而截断
     * @param wasByteTruncated 是否因字节数超限而截断
     */
    public record IndexContent(
            String content,
            int lineCount,
            int byteCount,
            boolean wasLineTruncated,
            boolean wasByteTruncated
    ) {}
}
