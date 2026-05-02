package io.sketch.mochaagents.orchestration.strategy;

import io.sketch.mochaagents.core.Agent;
import io.sketch.mochaagents.orchestration.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 群体策略 — 多个 Agent 并行处理同一任务，投票/择优产生最终结果.
 */
public class SwarmStrategy implements OrchestrationStrategy {

    private final int swarmSize;
    private final SwarmConsensus consensus;

    public enum SwarmConsensus { MAJORITY, BEST, FIRST, AGGREGATE }

    public SwarmStrategy(int swarmSize, SwarmConsensus consensus) {
        this.swarmSize = swarmSize;
        this.consensus = consensus;
    }

    public SwarmStrategy() {
        this(5, SwarmConsensus.BEST);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O execute(AgentTeam team, I input) {
        Collection<Agent<?, ?>> allAgents = team.getAgents();
        List<Agent<?, ?>> participants = allAgents.stream()
                .limit(swarmSize)
                .toList();

        // 并行执行
        List<CompletableFuture<Object>> futures = participants.stream()
                .map(a -> CompletableFuture.supplyAsync(() -> ((Agent<I, Object>) (Object) a).execute(input)))
                .toList();

        List<Object> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        return (O) switch (consensus) {
            case FIRST -> results.isEmpty() ? null : results.get(0);
            case BEST -> results.stream()
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            case AGGREGATE -> (Object) results;
            case MAJORITY -> mostFrequent(results);
        };
    }

    private Object mostFrequent(List<Object> results) {
        return results.stream()
                .filter(Objects::nonNull)
                .reduce((a, b) -> new HashMap<>() {{ put(a, 1); put(b, 1); }})
                .orElse(null);
    }
}
