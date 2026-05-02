package io.sketch.mochaagents.core;

import java.util.Collections;
import java.util.Set;

/**
 * Agent 元数据 — 名称、版本、能力集等静态描述信息.
 */
public final class AgentMetadata {

    private final String name;
    private final String version;
    private final String description;
    private final Set<String> capabilities;
    private final String modelInfo;

    private AgentMetadata(Builder builder) {
        this.name = builder.name;
        this.version = builder.version;
        this.description = builder.description;
        this.capabilities = Collections.unmodifiableSet(builder.capabilities);
        this.modelInfo = builder.modelInfo;
    }

    public String name() { return name; }
    public String version() { return version; }
    public String description() { return description; }
    public Set<String> capabilities() { return capabilities; }
    public String modelInfo() { return modelInfo; }

    /**
     * 合并两个元数据，name/version/description 取当前值.
     */
    public AgentMetadata and(AgentMetadata other) {
        return new Builder()
                .name(this.name)
                .version(this.version)
                .description(this.description)
                .capabilities(mergeSet(this.capabilities, other.capabilities))
                .modelInfo(this.modelInfo)
                .build();
    }

    private static Set<String> mergeSet(Set<String> a, Set<String> b) {
        java.util.Set<String> merged = new java.util.HashSet<>(a);
        merged.addAll(b);
        return Collections.unmodifiableSet(merged);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name = "unnamed";
        private String version = "1.0.0";
        private String description = "";
        private Set<String> capabilities = Collections.emptySet();
        private String modelInfo = "";

        public Builder name(String name) { this.name = name; return this; }
        public Builder version(String version) { this.version = version; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder capabilities(Set<String> capabilities) { this.capabilities = capabilities; return this; }
        public Builder modelInfo(String modelInfo) { this.modelInfo = modelInfo; return this; }

        public AgentMetadata build() {
            return new AgentMetadata(this);
        }
    }
}
