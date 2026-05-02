package io.sketch.mochaagents.perception.fusion;

import io.sketch.mochaagents.perception.Observation;
import io.sketch.mochaagents.perception.PerceptionResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文融合器 — 合并多个感知源的结果.
 */
public class ContextFuser {

    /**
     * 融合多个观察结果为一个上下文字符串.
     */
    public String fuse(List<Observation> observations) {
        StringBuilder sb = new StringBuilder();
        for (Observation obs : observations) {
            sb.append("[").append(obs.source()).append("] ").append(obs.content()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 融合多个感知结果为一个.
     */
    public PerceptionResult<String> fuseResults(List<PerceptionResult<Observation>> results) {
        List<Observation> obs = new ArrayList<>();
        double totalConfidence = 0;
        for (PerceptionResult<Observation> r : results) {
            if (r.data() != null) {
                obs.add(r.data());
                totalConfidence += r.confidence();
            }
        }
        double avgConfidence = obs.isEmpty() ? 0 : totalConfidence / obs.size();
        return PerceptionResult.of(fuse(obs), "fused", avgConfidence);
    }
}
