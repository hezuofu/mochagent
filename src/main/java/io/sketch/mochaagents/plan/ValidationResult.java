package io.sketch.mochaagents.plan;

/**
 * 验证结果.
 */
public final class ValidationResult {
    private final boolean valid;
    private final String message;

    public ValidationResult(boolean valid, String message) { this.valid = valid; this.message = message; }
    public boolean isValid() { return valid; }
    public String message() { return message; }

    public static ValidationResult valid() { return new ValidationResult(true, "OK"); }
    public static ValidationResult invalid(String msg) { return new ValidationResult(false, msg); }
}
