package io.sketch.mochaagents.orchestration;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.AgentListener;
import io.sketch.mochaagents.agent.AgentMetadata;
import io.sketch.mochaagents.orchestration.strategy.DebateStrategy;
import io.sketch.mochaagents.orchestration.strategy.SwarmStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OrchestratorIntegrationTest {

    private static Agent<String, String> agent(String name, String prefix) {
        return new Agent<>() {
            @Override public String execute(String input, AgentContext ctx) {
                return prefix + ":" + input;
            }
            @Override public CompletableFuture<String> executeAsync(String input, AgentContext ctx) {
                return CompletableFuture.completedFuture(execute(input, ctx));
            }
            @Override public AgentMetadata metadata() {
                return AgentMetadata.builder().name(name).build();
            }
            @Override public void addListener(AgentListener<String, String> l) {}
            @Override public void removeListener(AgentListener<String, String> l) {}
        };
    }

    // --- DefaultOrchestrator with sequential strategy ---

    @Test
    void sequentialStrategyChainsAgents() {
        DefaultOrchestrator orch = new DefaultOrchestrator();
        orch.register(agent("a1", "A"), Role.worker("a1"));
        orch.register(agent("a2", "B"), Role.worker("a2"));

        String result = orch.orchestrate("input", OrchestrationStrategy.sequential());
        assertEquals("B:A:input", result);
    }

    @Test
    void parallelStrategyReturnsAllResults() {
        DefaultOrchestrator orch = new DefaultOrchestrator();
        orch.register(agent("a1", "X"), Role.worker("a1"));
        orch.register(agent("a2", "Y"), Role.worker("a2"));

        @SuppressWarnings("unchecked")
        List<String> results = orch.orchestrate("task", OrchestrationStrategy.parallel());
        assertEquals(2, results.size());
        assertTrue(results.get(0).startsWith("X:"));
        assertTrue(results.get(1).startsWith("Y:"));
    }

    // --- SwarmStrategy ---

    @Test
    void swarmBestPicksFirstNonNullResult() {
        DefaultOrchestrator orch = new DefaultOrchestrator();
        orch.register(agent("a1", "P"), Role.worker("a1"));
        orch.register(agent("a2", "Q"), Role.worker("a2"));

        String result = orch.orchestrate("task", new SwarmStrategy(3, SwarmStrategy.SwarmConsensus.BEST));
        assertNotNull(result);
    }

    @Test
    void swarmMajoritySelectsMostFrequent() {
        DefaultOrchestrator orch = new DefaultOrchestrator();

        // All agents return same value
        orch.register(agent("a1", "VOTE_A"), Role.worker("a1"));
        orch.register(agent("a2", "VOTE_A"), Role.worker("a2"));

        String result = orch.orchestrate("task", new SwarmStrategy(3, SwarmStrategy.SwarmConsensus.MAJORITY));
        assertNotNull(result);
    }

    @Test
    void swarmAggregateReturnsAllResults() {
        DefaultOrchestrator orch = new DefaultOrchestrator();
        orch.register(agent("a1", "R1"), Role.worker("a1"));
        orch.register(agent("a2", "R2"), Role.worker("a2"));

        @SuppressWarnings("unchecked")
        List<Object> results = orch.orchestrate("task", new SwarmStrategy(3, SwarmStrategy.SwarmConsensus.AGGREGATE));
        assertEquals(2, results.size());
    }

    // --- DebateStrategy ---

    @Test
    void debateRunsRoundsAndProducesResult() {
        DefaultOrchestrator orch = new DefaultOrchestrator();
        orch.register(agent("debater1", "OpinionA"), Role.worker("debater1"));
        orch.register(agent("debater2", "OpinionB"), Role.worker("debater2"));

        String result = orch.orchestrate("topic", new DebateStrategy(2, 0.5));
        assertNotNull(result);
    }

    // --- Team management ---

    @Test
    void teamTracksMembersByRole() {
        DefaultOrchestrator orch = new DefaultOrchestrator();
        orch.register(agent("leader", "L"), Role.leader("leader"));
        orch.register(agent("worker1", "W1"), Role.worker("worker1"));
        orch.register(agent("worker2", "W2"), Role.worker("worker2"));

        AgentTeam team = orch.getTeam();
        assertEquals(3, team.memberCount());
        assertEquals(1, team.getLeaders().size());
        assertEquals(2, team.getByRole(RoleType.WORKER).size());
        assertEquals(1, team.getByRole(RoleType.LEADER).size());
    }

    @Test
    void unregisterRemovesFromTeam() {
        DefaultOrchestrator orch = new DefaultOrchestrator();
        orch.register(agent("temp", "T"), Role.worker("temp"));
        assertEquals(1, orch.agentCount());

        orch.unregister("temp");
        assertEquals(0, orch.agentCount());
        assertEquals(0, orch.getTeam().memberCount());
    }

    // --- Orchestrator lifecycle ---

    @Test
    void shutdownReleasesAllResources() {
        DefaultOrchestrator orch = new DefaultOrchestrator();
        orch.register(agent("a1", "X"), Role.worker("a1"));
        orch.register(agent("a2", "Y"), Role.worker("a2"));

        orch.shutdown();
        assertEquals(0, orch.agentCount());
        assertEquals(0, orch.getTeam().memberCount());
    }

    @Test
    void emptyTeamIsValid() {
        DefaultOrchestrator orch = new DefaultOrchestrator();
        assertEquals(0, orch.agentCount());
        assertNotNull(orch.getTeam());
    }
}
