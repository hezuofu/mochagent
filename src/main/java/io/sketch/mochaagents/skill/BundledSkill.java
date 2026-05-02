package io.sketch.mochaagents.skill;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 内置技能 — 编译进 JAR 的技能定义，对齐 claude-code 的 registerBundledSkill().
 *
 * <p>不可变值对象，通过 Builder 模式构造。典型用法:
 * <pre>{@code
 * BundledSkill skill = BundledSkill.builder("review-pr", "Review a pull request", SkillSource.BUNDLED)
 *     .whenToUse("Use when asked to review code changes")
 *     .prompt(args -> List.of(ContentBlock.text("Review the following: " + args)))
 *     .build();
 * }</pre>
 */
public final class BundledSkill implements Skill {

    private final String name;
    private final String description;
    private final List<String> aliases;
    private final String whenToUse;
    private final String argumentHint;
    private final String version;
    private final SkillSource source;
    private final boolean enabled;
    private final boolean userInvocable;
    private final SkillContext context;
    private final List<String> allowedTools;
    private final String model;
    private final boolean disableModelInvocation;
    private final Function<String, List<ContentBlock>> promptGenerator;

    private BundledSkill(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name must not be null");
        this.description = Objects.requireNonNull(builder.description, "description must not be null");
        this.aliases = List.copyOf(builder.aliases);
        this.whenToUse = builder.whenToUse;
        this.argumentHint = builder.argumentHint;
        this.version = builder.version;
        this.source = Objects.requireNonNull(builder.source, "source must not be null");
        this.enabled = builder.enabled;
        this.userInvocable = builder.userInvocable;
        this.context = builder.context;
        this.allowedTools = List.copyOf(builder.allowedTools);
        this.model = builder.model;
        this.disableModelInvocation = builder.disableModelInvocation;
        this.promptGenerator = Objects.requireNonNull(builder.promptGenerator, "promptGenerator must not be null");
    }

    // ==================== 核心标识 ====================

    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public List<String> aliases() { return aliases; }

    // ==================== 元数据 ====================

    @Override public String whenToUse() { return whenToUse; }
    @Override public String argumentHint() { return argumentHint; }
    @Override public String version() { return version; }
    @Override public SkillSource source() { return source; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public boolean isUserInvocable() { return userInvocable; }

    // ==================== 执行控制 ====================

    @Override public SkillContext context() { return context; }
    @Override public List<String> allowedTools() { return allowedTools; }
    @Override public String model() { return model; }
    @Override public boolean disableModelInvocation() { return disableModelInvocation; }

    // ==================== Prompt 生成 ====================

    @Override
    public List<ContentBlock> getPromptForCommand(String args) {
        return promptGenerator.apply(args != null ? args : "");
    }

    // ==================== Builder ====================

    public static Builder builder(String name, String description, SkillSource source) {
        return new Builder(name, description, source);
    }

    public static final class Builder {
        private final String name;
        private final String description;
        private final SkillSource source;
        private List<String> aliases = Collections.emptyList();
        private String whenToUse = "";
        private String argumentHint = "";
        private String version = "";
        private boolean enabled = true;
        private boolean userInvocable = true;
        private SkillContext context = SkillContext.INLINE;
        private List<String> allowedTools = Collections.emptyList();
        private String model = null;
        private boolean disableModelInvocation = false;
        private Function<String, List<ContentBlock>> promptGenerator = args -> Collections.emptyList();

        Builder(String name, String description, SkillSource source) {
            this.name = name;
            this.description = description;
            this.source = source;
        }

        public Builder aliases(List<String> v) { this.aliases = v; return this; }
        public Builder whenToUse(String v) { this.whenToUse = v; return this; }
        public Builder argumentHint(String v) { this.argumentHint = v; return this; }
        public Builder version(String v) { this.version = v; return this; }
        public Builder enabled(boolean v) { this.enabled = v; return this; }
        public Builder userInvocable(boolean v) { this.userInvocable = v; return this; }
        public Builder context(SkillContext v) { this.context = v; return this; }
        public Builder allowedTools(List<String> v) { this.allowedTools = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder disableModelInvocation(boolean v) { this.disableModelInvocation = v; return this; }
        public Builder prompt(Function<String, List<ContentBlock>> v) { this.promptGenerator = v; return this; }

        public BundledSkill build() {
            return new BundledSkill(this);
        }
    }
}
