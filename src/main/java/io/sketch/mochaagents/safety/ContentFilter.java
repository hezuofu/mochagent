package io.sketch.mochaagents.safety;

import java.util.List;

/**
 * 内容过滤器 — 检测和过滤不安全、不当的内容.
 * @author lanxia39@163.com
 */
public class ContentFilter {

    private final List<String> unsafePatterns;
    private final boolean strict;

    public ContentFilter(List<String> unsafePatterns, boolean strict) {
        this.unsafePatterns = List.copyOf(unsafePatterns);
        this.strict = strict;
    }

    public ContentFilter() {
        this(List.of("rm -rf", "sudo ", "DROP TABLE", "DELETE FROM",
                "eval(", "exec(", "__import__", "os.system",
                "subprocess", "sys.exit"), true);
    }

    /** 检查内容是否安全 */
    public boolean isSafe(String content) {
        if (content == null || content.isEmpty()) return true;
        String lower = content.toLowerCase();
        return unsafePatterns.stream().noneMatch(p -> lower.contains(p.toLowerCase()));
    }

    /** 过滤不安全内容，替换为安全标记 */
    public String filter(String content) {
        if (!strict) return content;
        String result = content;
        for (String pattern : unsafePatterns) {
            result = result.replaceAll("(?i)" + java.util.regex.Pattern.quote(pattern), "[FILTERED]");
        }
        return result;
    }
}
