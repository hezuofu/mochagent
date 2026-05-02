package io.sketch.mochaagents.safety;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全管理器 — 统一管理 Agent 操作的安全检查、内容过滤与审计.
 */
public class SafetyManager {

    private final ContentFilter contentFilter;
    private final CodeValidator codeValidator;
    private final Sandbox sandbox;
    private final List<String> blockedPatterns = new ArrayList<>();

    public SafetyManager(ContentFilter contentFilter, CodeValidator codeValidator, Sandbox sandbox) {
        this.contentFilter = contentFilter;
        this.codeValidator = codeValidator;
        this.sandbox = sandbox;
    }

    public SafetyManager() {
        this(new ContentFilter(), new CodeValidator(), new Sandbox());
    }

    /** 内容安全检查 */
    public boolean checkContent(String content) {
        return contentFilter.isSafe(content) && !matchesBlockedPattern(content);
    }

    /** 代码安全检查 */
    public boolean validateCode(String code) {
        return codeValidator.validate(code);
    }

    /** 在沙箱中安全执行 */
    public String safeExecute(String code, String language) {
        if (!validateCode(code)) return "Code rejected by safety validator";
        return sandbox.execute(code, language);
    }

    /** 添加屏蔽模式 */
    public void addBlockedPattern(String pattern) {
        blockedPatterns.add(pattern);
    }

    private boolean matchesBlockedPattern(String content) {
        return blockedPatterns.stream().anyMatch(content::contains);
    }
}
