package io.sketch.mochaagents.agent;

import io.sketch.mochaagents.memory.AgentMemory;

/**
 * Contract for agents that expose their execution memory to the agentic loop.
 * @author lanxia39@163.com
 */
public interface MemoryProvider {
    AgentMemory memory();
}
