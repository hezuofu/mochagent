package io.sketch.mochaagents.memory;

import io.sketch.mochaagents.agent.Agent;

/**
 * Contract for agents that expose their execution memory to the loop.
 */
public interface MemoryProvider {

    AgentMemory memory();

    static AgentMemory of(Agent<?, ?> agent) {
        return agent instanceof MemoryProvider mp ? mp.memory() : null;
    }
}
