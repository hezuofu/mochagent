package io.sketch.mochaagents.perception.processor;

import io.sketch.mochaagents.perception.Observation;
import io.sketch.mochaagents.perception.PerceptionResult;
import io.sketch.mochaagents.perception.Perceptor;

import java.util.concurrent.CompletableFuture;

/**
 * 文件系统感知器 — 感知文件内容与结构.
 * @author lanxia39@163.com
 */
public class FileSystemPerceptor implements Perceptor<String, Observation> {

    @Override
    public PerceptionResult<Observation> perceive(String filePath) {
        Observation obs = new Observation("filesystem", filePath, "file");
        return PerceptionResult.of(obs, "file");
    }

    @Override
    public CompletableFuture<PerceptionResult<Observation>> perceiveAsync(String input) {
        return CompletableFuture.completedFuture(perceive(input));
    }
}
