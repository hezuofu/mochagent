package io.sketch.mochaagents.perception.processor;

import io.sketch.mochaagents.perception.Observation;
import io.sketch.mochaagents.perception.PerceptionResult;
import io.sketch.mochaagents.perception.Perceptor;

import java.util.concurrent.CompletableFuture;

/**
 * 终端感知器 — 感知终端命令输出.
 * @author lanxia39@163.com
 */
public class TerminalPerceptor implements Perceptor<String, Observation> {

    @Override
    public PerceptionResult<Observation> perceive(String commandOutput) {
        Observation obs = new Observation("terminal", commandOutput, "terminal");
        return PerceptionResult.of(obs, "terminal");
    }

    @Override
    public CompletableFuture<PerceptionResult<Observation>> perceiveAsync(String input) {
        return CompletableFuture.completedFuture(perceive(input));
    }
}
