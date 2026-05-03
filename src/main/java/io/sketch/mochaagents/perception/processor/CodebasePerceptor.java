package io.sketch.mochaagents.perception.processor;

import io.sketch.mochaagents.perception.Observation;
import io.sketch.mochaagents.perception.PerceptionResult;
import io.sketch.mochaagents.perception.Perceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 代码库感知器 — 扫描目录结构、统计文件类型，感知项目概貌.
 * @author lanxia39@163.com
 */
public class CodebasePerceptor implements Perceptor<String, Observation> {

    private static final Logger log = LoggerFactory.getLogger(CodebasePerceptor.class);
    private final int maxFiles;
    private final int maxDepth;

    public CodebasePerceptor() { this(200, 5); }

    public CodebasePerceptor(int maxFiles, int maxDepth) {
        this.maxFiles = maxFiles; this.maxDepth = maxDepth;
    }

    @Override
    public PerceptionResult<Observation> perceive(String path) {
        StringBuilder sb = new StringBuilder();
        try {
            Path root = Paths.get(path);
            if (!Files.exists(root)) {
                return PerceptionResult.of(
                        new Observation("codebase", "Path not found: " + path, "codebase"), "codebase");
            }
            var fileCount = new java.util.concurrent.atomic.AtomicInteger(0);
            Files.walk(root, maxDepth)
                    .filter(Files::isRegularFile)
                    .limit(maxFiles)
                    .forEach(f -> {
                        sb.append(f.getFileName()).append("\n");
                        fileCount.incrementAndGet();
                    });
            String summary = String.format("Project at %s: %d files scanned (max %d, depth %d)\n%s",
                    root.toAbsolutePath(), fileCount.get(), maxFiles, maxDepth, sb.toString());
            log.debug("CodebasePerceptor scanned {} files", fileCount.get());
            return PerceptionResult.of(
                    new Observation("codebase", summary, "codebase"), "codebase");
        } catch (IOException e) {
            log.warn("CodebasePerceptor failed: {}", e.getMessage());
            return PerceptionResult.of(
                    new Observation("codebase", "Error scanning: " + e.getMessage(), "codebase"), "codebase");
        }
    }

    @Override
    public CompletableFuture<PerceptionResult<Observation>> perceiveAsync(String input) {
        return CompletableFuture.supplyAsync(() -> perceive(input));
    }
}
