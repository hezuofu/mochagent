package io.sketch.mochaagents.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全管理器 — 统一管理 Agent 操作的安全检查、内容过滤与审计.
 */
public class SafetyManager {

    private static final Logger log = LoggerFactory.getLogger(SafetyManager.class);

    private final ContentFilter contentFilter;
    private final CodeValidator codeValidator;
    private final Sandbox sandbox;
    private final List<String> blockedPatterns = new ArrayList<>();

    public SafetyManager(ContentFilter contentFilter, CodeValidator codeValidator, Sandbox sandbox) {
        this.contentFilter = contentFilter;
        this.codeValidator = codeValidator;
        this.sandbox = sandbox;
        log.info("SafetyManager initialized");
    }

    public SafetyManager() {
        this(new ContentFilter(), new CodeValidator(), new Sandbox());
    }

    /** 内容安全检查 */
    public boolean checkContent(String content) {
        boolean safe = contentFilter.isSafe(content) && !matchesBlockedPattern(content);
        if (!safe) log.warn("Content rejected by safety filter");
        return safe;
    }

    /** 代码安全检查 */
    public boolean validateCode(String code) {
        boolean valid = codeValidator.validate(code);
        if (!valid) log.warn("Code rejected by safety validator");
        return valid;
    }

    /** 在沙箱中安全执行 */
    public String safeExecute(String code, String language) {
        if (!validateCode(code)) {
            log.warn("Code execution blocked by safety, language={}", language);
            return "Code rejected by safety validator";
        }
        log.debug("Executing code safely in sandbox, language={}", language);
        String result = sandbox.execute(code, language);
        log.debug("Sandbox execution completed");
        return result;
    }

    /** 添加屏蔽模式 */
    public void addBlockedPattern(String pattern) {
        blockedPatterns.add(pattern);
        log.debug("Blocked pattern added: {}", pattern);
    }

    private boolean matchesBlockedPattern(String content) {
        return blockedPatterns.stream().anyMatch(content::contains);
    }
}
