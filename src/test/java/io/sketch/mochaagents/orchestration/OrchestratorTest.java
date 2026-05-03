package io.sketch.mochaagents.orchestration;

import io.sketch.mochaagents.agent.Agent;
import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.AgentListener;
import io.sketch.mochaagents.agent.AgentMetadata;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class OrchestratorTest {

    private static Agent<String, String> dummyAgent(String name) {
        return new Agent<>() {
            @Override public String execute(String input, AgentContext ctx) { return name + ":" + input; }
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

    @Test
    void registerAndOrchestrate() {
        DefaultOrchestrator orch = new DefaultOrchestrator();
        orch.register(dummyAgent("agent1"), Role.worker("worker"));
        orch.register(dummyAgent("agent2"), Role.leader("leader"));

        assertEquals(2, orch.agentCount());
        assertEquals(2, orch.getTeam().memberCount());
    }

    @Test
    void unregisterRemovesAgent() {
        DefaultOrchestrator orch = new DefaultOrchestrator();
        orch.register(dummyAgent("temp"), Role.worker("temp"));
        assertEquals(1, orch.agentCount());

        orch.unregister("temp");
        assertEquals(0, orch.agentCount());
    }

    @Test
    void shutdownClearsAll() {
        DefaultOrchestrator orch = new DefaultOrchestrator();
        orch.register(dummyAgent("a1"), Role.worker("r1"));
        orch.register(dummyAgent("a2"), Role.worker("r2"));

        orch.shutdown();
        assertEquals(0, orch.agentCount());
    }
}
