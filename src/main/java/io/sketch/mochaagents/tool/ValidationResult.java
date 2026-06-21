// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.tool;

import java.util.Map;

/**
 * 工具输入校验结果 — 对齐 claude-code 的 ValidationResult.
 *
 * <pre>
 *   valid=true  → 通过校验，继续执行
 *   valid=false → 校验失败，含错误码和消息
 * </pre>
 * @author lanxia39@163.com
 */
public final class ValidationResult {

    private final boolean valid;
    private final String message;
    private final int errorCode;
    private final Map<String, Object> meta;
    private final Object typedValue;

    private ValidationResult(boolean valid, String message, int errorCode, Map<String, Object> meta, Object typedValue) {
        this.valid = valid;
        this.message = message;
        this.errorCode = errorCode;
        this.meta = meta;
        this.typedValue = typedValue;
    }

    /** 校验通过. */
    public static ValidationResult valid() {
        return new ValidationResult(true, null, 0, null, null);
    }

    /** 校验通过，带元数据. */
    public static ValidationResult valid(Map<String, Object> meta) {
        return new ValidationResult(true, null, 0, meta, null);
    }

    /** 校验通过，带转换后的类型化值 (Pydantic 模式). */
    public static ValidationResult validWith(Object typedValue) {
        return new ValidationResult(true, null, 0, null, typedValue);
    }

    /** 校验失败. */
    public static ValidationResult invalid(String message, int errorCode) {
        return new ValidationResult(false, message, errorCode, null, null);
    }

    /** 校验失败 (无 errorCode). */
    public static ValidationResult invalid(String message) {
        return new ValidationResult(false, message, 0, null, null);
    }

    /** 校验失败，带元数据. */
    public static ValidationResult invalid(String message, int errorCode, Map<String, Object> meta) {
        return new ValidationResult(false, message, errorCode, meta, null);
    }

    public boolean isValid() { return valid; }
    public String getMessage() { return message; }
    public int getErrorCode() { return errorCode; }
    public Map<String, Object> getMeta() { return meta; }

    /** Typed record value from schema validation (null if validation used Map-only path). */
    @SuppressWarnings("unchecked")
    public <T> T typedValue() { return (T) typedValue; }
    public boolean hasTypedValue() { return typedValue != null; }

    @Override
    public String toString() {
        return valid ? "ValidationResult{valid}" : "ValidationResult{invalid, code=" + errorCode + ", " + message + "}";
    }
}
