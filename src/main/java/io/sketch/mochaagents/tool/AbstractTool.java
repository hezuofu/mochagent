package io.sketch.mochaagents.tool;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 工具抽象基类 — 对齐 claude-code 的 {@code buildTool() + TOOL_DEFAULTS} 模式.
 *
 * <p>提供安全的 fail-closed 默认实现，子类只需覆盖核心方法:
 * <pre>{@code
 * public class MyTool extends AbstractTool {
 *     public MyTool() {
 *         super("my_tool", "Does something useful", SecurityLevel.MEDIUM);
 *     }
 *
 *     &#64;Override public Object call(Map<String, Object> args) { ... }
 *     &#64;Override public ToolSchema getSchema() { ... }
 * }
 * }</pre>
 *
 * <p>默认安全策略:
 * <ul>
 *   <li>isReadOnly → false (fail-closed)</li>
 *   <li>isConcurrencySafe → false (fail-closed)</li>
 *   <li>isDestructive → false</li>
 *   <li>checkPermissions → allow (default)</li>
 * </ul>
 */
public abstract class AbstractTool implements Tool {

    private final String name;
    private final String description;
    private final List<String> aliases;
    private final String searchHint;
    private final SecurityLevel securityLevel;
    private final boolean readOnly;
    private final boolean concurrencySafe;
    private final boolean destructive;

    protected AbstractTool(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.aliases = Collections.unmodifiableList(builder.aliases);
        this.searchHint = builder.searchHint;
        this.securityLevel = builder.securityLevel;
        this.readOnly = builder.readOnly;
        this.concurrencySafe = builder.concurrencySafe;
        this.destructive = builder.destructive;
    }

    /** 便捷构造器（无别称、无搜索提示）. */
    protected AbstractTool(String name, String description, SecurityLevel securityLevel) {
        this(builder(name, description, securityLevel));
    }

    // ==================== 核心标识 ====================

    @Override public String getName() { return name; }
    @Override public String getDescription() { return description; }
    @Override public List<String> getAliases() { return aliases; }
    @Override public String getSearchHint() { return searchHint; }
    @Override public SecurityLevel getSecurityLevel() { return securityLevel; }

    // ==================== 安全属性（子类通过 Builder 或覆盖方法调整） ====================

    @Override public boolean isReadOnly() { return readOnly; }
    @Override public boolean isConcurrencySafe() { return concurrencySafe; }
    @Override public boolean isDestructive() { return destructive; }

    // ==================== Schema（子类覆盖） ====================

    @Override public ToolSchema getSchema() {
        return ToolSchema.inputOnly(Collections.emptyMap());
    }

    // ==================== 校验与权限 ====================

    @Override public ValidationResult validateInput(Map<String, Object> arguments) {
        return ValidationResult.valid();
    }

    @Override public PermissionResult checkPermissions(Map<String, Object> arguments) {
        return PermissionResult.allow(arguments);
    }

    // ==================== 异步调用 ====================

    @Override public CompletableFuture<Object> callAsync(Map<String, Object> arguments) {
        return CompletableFuture.supplyAsync(() -> call(arguments));
    }

    // ==================== 结果格式化 ====================

    @Override public String formatResult(Object output, String toolUseId) {
        return output != null ? output.toString() : "";
    }

    @Override public String getUserFacingName() { return name; }

    // ==================== Builder ====================

    public static Builder builder(String name, String description, SecurityLevel securityLevel) {
        return new Builder(name, description, securityLevel);
    }

    public static final class Builder {
        private final String name;
        private final String description;
        private final SecurityLevel securityLevel;
        private List<String> aliases = Collections.emptyList();
        private String searchHint = "";
        private boolean readOnly = false;
        private boolean concurrencySafe = false;
        private boolean destructive = false;

        Builder(String name, String description, SecurityLevel securityLevel) {
            this.name = name;
            this.description = description;
            this.securityLevel = securityLevel;
        }

        public Builder aliases(List<String> v) { this.aliases = v; return this; }
        public Builder searchHint(String v) { this.searchHint = v; return this; }
        public Builder readOnly(boolean v) { this.readOnly = v; return this; }
        public Builder concurrencySafe(boolean v) { this.concurrencySafe = v; return this; }
        public Builder destructive(boolean v) { this.destructive = v; return this; }

        // 子类通过匿名子类化 Builder 来实例化自己的工具类
        public <T extends AbstractTool> T build(java.util.function.Function<Builder, T> factory) {
            return factory.apply(this);
        }
    }
}
