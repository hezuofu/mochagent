package io.sketch.mochaagents.perception.processor;

import io.sketch.mochaagents.perception.Observation;
import io.sketch.mochaagents.perception.PerceptionResult;
import io.sketch.mochaagents.perception.Perceptor;

import java.util.concurrent.CompletableFuture;

/**
 * 代码库感知器 — 感知项目结构和代码内容.
 * @author lanxia39@163.com
 */
public class CodebasePerceptor implements Perceptor<String, Observation> {

    @Override
    public PerceptionResult<Observation> perceive(String path) {
        Observation obs = new Observation("codebase", path, "codebase");
        return PerceptionResult.of(obs, "codebase");
    }

    @Override
    public CompletableFuture<PerceptionResult<Observation>> perceiveAsync(String input) {
        return CompletableFuture.completedFuture(perceive(input));
    }
}
