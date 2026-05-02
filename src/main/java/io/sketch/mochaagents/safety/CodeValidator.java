package io.sketch.mochaagents.safety;

import java.util.List;

/**
 * 代码验证器 — 对 AI 生成的代码进行安全和质量验证.
 */
public class CodeValidator {

    private final List<String> dangerousImports;
    private final int maxCodeLength;

    public CodeValidator(List<String> dangerousImports, int maxCodeLength) {
        this.dangerousImports = List.copyOf(dangerousImports);
        this.maxCodeLength = maxCodeLength;
    }

    public CodeValidator() {
        this(List.of("java.lang.Runtime", "java.lang.ProcessBuilder",
                "java.lang.reflect", "sun.misc.Unsafe",
                "os", "subprocess", "shutil", "socket"), 100000);
    }

    /** 验证代码安全性 */
    public boolean validate(String code) {
        if (code == null || code.isEmpty()) return true;
        if (code.length() > maxCodeLength) return false;

        String lower = code.toLowerCase();
        for (String dangerous : dangerousImports) {
            if (lower.contains(dangerous.toLowerCase())) {
                return false;
            }
        }
        return true;
    }

    /** 获取验证报告 */
    public ValidationReport report(String code) {
        boolean safe = validate(code);
        List<String> issues = dangerousImports.stream()
                .filter(d -> code != null && code.toLowerCase().contains(d.toLowerCase()))
                .map(d -> "Dangerous import detected: " + d)
                .toList();
        return new ValidationReport(safe, issues);
    }

    public record ValidationReport(boolean safe, List<String> issues) {}
}
