package io.sketch.mochaagents.agent;

/**
 * Minimal agent identity — name and description.
 * Merged via {@link #and(AgentMetadata)} when composing agents.
 */
public record AgentMetadata(String name, String description) {

    public AgentMetadata(String name) { this(name, ""); }

    public AgentMetadata and(AgentMetadata other) {
        return new AgentMetadata(
                this.name,
                (this.description + " " + other.description).trim());
    }
}
