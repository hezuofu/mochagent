package io.sketch.mochaagents.prompt;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 模板 — 基于 {@code {key}} 占位符的变量插值渲染器.
 *
 * <p>替代 Jinja2，使用简单字符串替换实现模板填充，不引入外部依赖.
 *
 * <h3>用法</h3>
 * <pre>{@code
 * PromptTemplate t = new PromptTemplate("Hello, {name}. Your task: {task}");
 * String result = t.render(Map.of("name", "Agent", "task", "solve it"));
 * }</pre>
 * @author lanxia39@163.com
 */
public final class PromptTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)\\}");

    private final String template;

    public PromptTemplate(String template) {
        this.template = template;
    }

    /**
     * 使用变量映射渲染模板.
     *
     * @param variables 变量名到值的映射
     * @return 渲染后的字符串
     */
    public String render(Map<String, Object> variables) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object value = variables.getOrDefault(key, "");
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 便捷方法 — 用两个变量渲染.
     */
    public String render(String k1, Object v1, String k2, Object v2) {
        return render(Map.of(k1, v1, k2, v2));
    }

    /**
     * 便捷方法 — 用单个变量渲染.
     */
    public String render(String key, Object value) {
        return render(Map.of(key, value));
    }

    /** 返回原始模板字符串. */
    public String template() {
        return template;
    }

    /** 从字符串创建模板. */
    public static PromptTemplate of(String template) {
        return new PromptTemplate(template);
    }

    @Override
    public String toString() {
        return template;
    }
}
