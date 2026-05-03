package io.sketch.mochaagents.perception.processor;

import io.sketch.mochaagents.perception.Observation;
import io.sketch.mochaagents.perception.PerceptionResult;
import io.sketch.mochaagents.perception.Perceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * 文件系统感知器 — 读取文件内容、列出目录、获取文件元数据.
 * @author lanxia39@163.com
 */
public class FileSystemPerceptor implements Perceptor<String, Observation> {

    private static final Logger log = LoggerFactory.getLogger(FileSystemPerceptor.class);
    private static final int MAX_CONTENT_LENGTH = 10000;

    @Override
    public PerceptionResult<Observation> perceive(String path) {
        try {
            Path p = Paths.get(path);
            if (!Files.exists(p)) {
                return PerceptionResult.of(
                        new Observation("filesystem", "Not found: " + path, "file"), "file");
            }
            if (Files.isDirectory(p)) {
                StringBuilder sb = new StringBuilder("Directory: ").append(path).append("\n");
                try (Stream<Path> entries = Files.list(p)) {
                    entries.limit(50).forEach(e -> {
                        String type = Files.isDirectory(e) ? " [DIR]" : " [FILE]";
                        try {
                            sb.append("  ").append(e.getFileName()).append(type)
                              .append(" (").append(Files.size(e)).append(" bytes)\n");
                        } catch (IOException ignored) {}
                    });
                }
                return PerceptionResult.of(
                        new Observation("filesystem", sb.toString(), "directory"), "file");
            }
            // Read file
            String content = Files.readString(p);
            if (content.length() > MAX_CONTENT_LENGTH) {
                content = content.substring(0, MAX_CONTENT_LENGTH) + "...(truncated)";
            }
            String meta = String.format("File: %s (%d bytes, %d lines)",
                    p.getFileName(), Files.size(p), content.lines().count());
            return PerceptionResult.of(
                    new Observation("filesystem", meta + "\n" + content, "file"), "file");
        } catch (IOException e) {
            log.warn("FileSystemPerceptor failed for {}: {}", path, e.getMessage());
            return PerceptionResult.of(
                    new Observation("filesystem", "Error: " + e.getMessage(), "file"), "file");
        }
    }

    @Override
    public CompletableFuture<PerceptionResult<Observation>> perceiveAsync(String input) {
        return CompletableFuture.supplyAsync(() -> perceive(input));
    }
}
