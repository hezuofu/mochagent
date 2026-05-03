package io.sketch.mochaagents.tool;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 工具接口 — 核心工具抽象，对齐 claude-code Tool 契约.
 *
 * <p>设计要点:
 * <ul>
 *   <li>同步 call() + 异步 callAsync() 双模式</li>
 *   <li>default 方法提供安全 fail-closed 默认值，子类可按需覆盖</li>
 *   <li>validateInput / checkPermissions 管道化校验链路</li>
 * </ul>
 * @author lanxia39@163.com
 */
public interface Tool {

    // ==================== 核心标识 ====================

    /** 工具唯一名称，供 LLM 调用和注册表查找. */
    String getName();

    /** 工具功能描述，写入 system prompt 供 LLM 理解. */
    String getDescription();

    /** 工具别称列表，支持向后兼容重命名. */
    default List<String> getAliases() { return Collections.emptyList(); }

    /** 搜索提示词（3-10 词），供 ToolSearch 关键词匹配. */
    default String getSearchHint() { return ""; }

    // ==================== Schema 描述 ====================

    /** 输入参数描述（旧接口，建议迁移到 getSchema().getInputSchema()）. */
    Map<String, ToolInput> getInputs();

    /** 输出类型描述（旧接口）. */
    String getOutputType();

    /**
     * 工具 JSON Schema 描述（输入+输出结构），对齐 claude-code 的 Zod schema.
     * 默认从 getInputs() 推导，建议子类显式提供。
     */
    default ToolSchema getSchema() {
        return ToolSchema.inputOnly(Collections.emptyMap());
    }

    // ==================== 调用契约 ====================

    /** 同步调用（保留向后兼容）. */
    Object call(Map<String, Object> arguments);

    /**
     * 异步调用 — 对齐 claude-code 的 async call().
     * 默认委托同步 call()，子类可覆盖实现真正的异步逻辑。
     */
    default CompletableFuture<Object> callAsync(Map<String, Object> arguments) {
        return CompletableFuture.supplyAsync(() -> call(arguments));
    }

    // ==================== 校验与权限 ====================

    /**
     * 输入校验 — 对齐 claude-code 的 validateInput().
     * 默认通过，需要校验的工具覆盖此方法。
     */
    default ValidationResult validateInput(Map<String, Object> arguments) {
        return ValidationResult.valid();
    }

    /**
     * 权限检查 — 对齐 claude-code 的 checkPermissions().
     * 默认允许，需要权限控制的工具覆盖此方法。
     */
    default PermissionResult checkPermissions(Map<String, Object> arguments) {
        return PermissionResult.allow(arguments);
    }

    // ==================== 安全属性 ====================

    /** 是否为只读工具（不修改文件系统/外部状态）. 默认 false（fail-closed）. */
    default boolean isReadOnly() { return false; }

    /** 是否为并发安全的（可多个实例同时执行）. 默认 false（fail-closed）. */
    default boolean isConcurrencySafe() { return false; }

    /** 是否为破坏性操作（删除、覆盖、发送等不可逆操作）. 默认 false. */
    default boolean isDestructive() { return false; }

    /** 安全等级 — 保留原有枚举. */
    SecurityLevel getSecurityLevel();

    /** 工具是否启用. 默认 true. */
    default boolean isEnabled() { return true; }

    // ==================== 结果格式化 ====================

    /**
     * 将工具输出格式化为 LLM 可读的结果块.
     * 对齐 claude-code 的 mapToolResultToToolResultBlockParam().
     */
    default String formatResult(Object output, String toolUseId) {
        return output != null ? output.toString() : "";
    }

    /** 渲染用户可读的工具名称（用于 UI 展示）. 默认返回 name. */
    default String getUserFacingName() { return getName(); }

    /** 生成人类可读的活动描述（用于进度报告，对齐claude-code的getActivityDescription）. */
    default String describeActivity(Map<String, Object> args) {
        return getName() + (args != null && !args.isEmpty() ? "(" + args.keySet() + ")" : "");
    }

    // ==================== 安全等级枚举 ====================

    enum SecurityLevel { LOW, MEDIUM, HIGH, CRITICAL }
}
