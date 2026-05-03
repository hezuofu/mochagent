package io.sketch.mochaagents.perception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 组合感知器 — 并行运行多个感知器，融合结果为统一的环境快照.
 *
 * <p>典型用法：同时感知代码库 + 文件系统 + 浏览器 + 终端，
 * 合并为 Agent 可用的综合感知上下文.
 * @author lanxia39@163.com
 */
public class CompositePerceptor<I, O> implements Perceptor<I, O> {

    private static final Logger log = LoggerFactory.getLogger(CompositePerceptor.class);

    private final List<Perceptor<I, O>> sensors;
    private final MergeStrategy mergeStrategy;

    @FunctionalInterface
    public interface MergeStrategy<O> {
        O merge(List<O> results);
    }

    @SafeVarargs
    public CompositePerceptor(Perceptor<I, O>... sensors) {
        this(List.of(sensors), null);
    }

    public CompositePerceptor(List<Perceptor<I, O>> sensors, MergeStrategy<O> mergeStrategy) {
        this.sensors = List.copyOf(sensors);
        this.mergeStrategy = mergeStrategy;
    }

    @Override
    @SuppressWarnings("unchecked")
    public PerceptionResult<O> perceive(I input) {
        List<PerceptionResult<O>> results = new ArrayList<>();
        double totalConfidence = 0;

        for (Perceptor<I, O> sensor : sensors) {
            try {
                PerceptionResult<O> r = sensor.perceive(input);
                results.add(r);
                totalConfidence += r.confidence();
            } catch (Exception e) {
                log.warn("Sensor {} failed: {}", sensor.getClass().getSimpleName(), e.getMessage());
            }
        }

        if (results.isEmpty()) {
            return PerceptionResult.of(null, "composite", 0.0);
        }

        // Pick first non-null data (caller can use mergeStrategy for custom logic)
        O mergedData = null;
        for (PerceptionResult<O> r : results) {
            if (r.data() != null) { mergedData = r.data(); break; }
        }

        double avgConfidence = results.isEmpty() ? 0 : totalConfidence / results.size();
        return PerceptionResult.of(mergedData, "composite", avgConfidence);
    }

    @Override
    public CompletableFuture<PerceptionResult<O>> perceiveAsync(I input) {
        List<CompletableFuture<PerceptionResult<O>>> futures = new ArrayList<>();
        for (Perceptor<I, O> sensor : sensors) {
            futures.add(sensor.perceiveAsync(input));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<PerceptionResult<O>> results = futures.stream()
                            .map(CompletableFuture::join).toList();
                    double avgConf = results.stream().mapToDouble(PerceptionResult::confidence)
                            .average().orElse(0.5);
                    O data = results.isEmpty() ? null : results.get(0).data();
                    return PerceptionResult.of(data, "composite", avgConf);
                });
    }

    /** 已注册的感知器数量 */
    public int sensorCount() { return sensors.size(); }
}
