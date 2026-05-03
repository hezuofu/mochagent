package io.sketch.mochaagents.perception;

import java.util.concurrent.CompletableFuture;

/**
 * 感知器接口 — 感知和处理各类输入.
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 * @author lanxia39@163.com
 */
public interface Perceptor<I, O> {

    PerceptionResult<O> perceive(I input);

    CompletableFuture<PerceptionResult<O>> perceiveAsync(I input);

    default Perceptor<I, O> filter(java.util.function.Predicate<O> filter) {
        Perceptor<I, O> self = this;
        return new Perceptor<>() {
            @Override public PerceptionResult<O> perceive(I input) {
                PerceptionResult<O> result = self.perceive(input);
                if (result.data() != null && !filter.test(result.data())) {
                    return PerceptionResult.empty();
                }
                return result;
            }
            @Override public CompletableFuture<PerceptionResult<O>> perceiveAsync(I input) {
                return self.perceiveAsync(input).thenApply(r -> {
                    if (r.data() != null && !filter.test(r.data())) {
                        return PerceptionResult.empty();
                    }
                    return r;
                });
            }
        };
    }
}
