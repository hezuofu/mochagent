package io.sketch.mochaagents.orchestration.strategy;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.orchestration.*;
import java.util.*;

/**
 * 辩论策略 — 多个 Agent 各自提出方案，通过辩论达成共识.
 */
public class DebateStrategy implements OrchestrationStrategy {

    private final int maxRounds;
    private final double consensusThreshold;

    public DebateStrategy(int maxRounds, double consensusThreshold) {
        this.maxRounds = maxRounds;
        this.consensusThreshold = consensusThreshold;
    }

    public DebateStrategy() {
        this(3, 0.7);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O execute(AgentTeam team, I input) {
        Collection<Agent<?, ?>> agents = team.getAgents();
        List<DebateResult> opinions = new ArrayList<>();

        for (int round = 0; round < maxRounds; round++) {
            for (Agent<?, ?> agent : agents) {
                String opinion = ((Agent<I, String>) (Object) agent).execute(input).toString();
                double confidence = Math.random(); // simulate confidence
                opinions.add(new DebateResult(agent.metadata().name(), opinion, confidence));
            }

            if (hasConsensus(opinions)) break;
        }

        return (O) opinions.stream()
                .max(Comparator.comparingDouble(DebateResult::confidence))
                .map(DebateResult::opinion)
                .orElse(null);
    }

    private boolean hasConsensus(List<DebateResult> opinions) {
        if (opinions.size() < 2) return false;
        Map<String, Long> counts = new HashMap<>();
        for (DebateResult r : opinions) counts.merge(r.opinion(), 1L, Long::sum);
        long maxCount = counts.values().stream().max(Long::compare).orElse(0L);
        return (double) maxCount / opinions.size() >= consensusThreshold;
    }

    private record DebateResult(String agentId, String opinion, double confidence) {}
}
