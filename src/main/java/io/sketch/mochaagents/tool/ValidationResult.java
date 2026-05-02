package io.sketch.mochaagents.tool;

import java.util.Map;

/**
 * 工具输入校验结果 — 对齐 claude-code 的 ValidationResult.
 *
 * <pre>
 *   valid=true  → 通过校验，继续执行
 *   valid=false → 校验失败，含错误码和消息
 * </pre>
 */
public final class ValidationResult {

    private final boolean valid;
    private final String message;
    private final int errorCode;
    private final Map<String, Object> meta;

    private ValidationResult(boolean valid, String message, int errorCode, Map<String, Object> meta) {
        this.valid = valid;
        this.message = message;
        this.errorCode = errorCode;
        this.meta = meta;
    }

    /** 校验通过. */
    public static ValidationResult valid() {
        return new ValidationResult(true, null, 0, null);
    }

    /** 校验通过，带元数据. */
    public static ValidationResult valid(Map<String, Object> meta) {
        return new ValidationResult(true, null, 0, meta);
    }

    /** 校验失败. */
    public static ValidationResult invalid(String message, int errorCode) {
        return new ValidationResult(false, message, errorCode, null);
    }

    /** 校验失败，带元数据. */
    public static ValidationResult invalid(String message, int errorCode, Map<String, Object> meta) {
        return new ValidationResult(false, message, errorCode, meta);
    }

    public boolean isValid() { return valid; }
    public String getMessage() { return message; }
    public int getErrorCode() { return errorCode; }
    public Map<String, Object> getMeta() { return meta; }

    @Override
    public String toString() {
        return valid ? "ValidationResult{valid}" : "ValidationResult{invalid, code=" + errorCode + ", " + message + "}";
    }
}
