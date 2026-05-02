package io.sketch.mochaagents.orchestration;

/**
 * Agent 角色 — 定义 Agent 在团队中的职责与能力范围.
 */
public class Role {

    private final String name;
    private final RoleType type;
    private final String description;
    private final java.util.Set<String> capabilities;

    public Role(String name, RoleType type, String description, java.util.Set<String> capabilities) {
        this.name = name;
        this.type = type;
        this.description = description;
        this.capabilities = java.util.Set.copyOf(capabilities);
    }

    public String name() { return name; }
    public RoleType type() { return type; }
    public String description() { return description; }
    public java.util.Set<String> capabilities() { return capabilities; }

    public boolean hasCapability(String capability) {
        return capabilities.contains(capability);
    }

    public static Role leader(String name) {
        return new Role(name, RoleType.LEADER, "Team leader: " + name, java.util.Set.of("orchestrate", "decide"));
    }

    public static Role worker(String name, String... capabilities) {
        return new Role(name, RoleType.WORKER, "Worker: " + name, java.util.Set.of(capabilities));
    }

    public static Role reviewer(String name) {
        return new Role(name, RoleType.REVIEWER, "Reviewer: " + name, java.util.Set.of("review", "validate"));
    }

    public static Role observer(String name) {
        return new Role(name, RoleType.OBSERVER, "Observer: " + name, java.util.Set.of("monitor", "report"));
    }
}
