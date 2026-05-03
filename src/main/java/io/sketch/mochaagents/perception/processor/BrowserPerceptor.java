package io.sketch.mochaagents.perception.processor;

import io.sketch.mochaagents.perception.Observation;
import io.sketch.mochaagents.perception.PerceptionResult;
import io.sketch.mochaagents.perception.Perceptor;

import java.util.concurrent.CompletableFuture;

/**
 * 浏览器感知器 — 感知网页内容和 DOM.
 * @author lanxia39@163.com
 */
public class BrowserPerceptor implements Perceptor<String, Observation> {

    @Override
    public PerceptionResult<Observation> perceive(String url) {
        Observation obs = new Observation("browser", url, "web");
        return PerceptionResult.of(obs, "web");
    }

    @Override
    public CompletableFuture<PerceptionResult<Observation>> perceiveAsync(String input) {
        return CompletableFuture.completedFuture(perceive(input));
    }
}
