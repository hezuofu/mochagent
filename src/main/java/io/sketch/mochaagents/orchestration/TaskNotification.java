package io.sketch.mochaagents.orchestration;

/**
 * 任务通知 XML 格式 — 对齐 claude-code 的 &lt;task-notification&gt;.
 *
 * <p>Worker 完成后生成的通知消息，由 Coordinator 解析。
 * @author lanxia39@163.com
 */
public final class TaskNotification {

    private TaskNotification() {}

    /**
     * 生成 task-notification XML.
     *
     * @param taskId worker agent ID
     * @param status completed / failed / killed
     * @param summary 人类可读的状态摘要
     * @param result agent 的最终文本响应
     * @param totalTokens 总 token 数
     * @param toolUses 工具调用次数
     * @param durationMs 执行时长（毫秒）
     * @return XML 字符串
     */
    public static String toXml(
            String taskId,
            String status,
            String summary,
            String result,
            long totalTokens,
            int toolUses,
            long durationMs) {

        StringBuilder sb = new StringBuilder();
        sb.append("<task-notification>\n");
        sb.append("  <task-id>").append(escape(taskId)).append("</task-id>\n");
        sb.append("  <status>").append(escape(status)).append("</status>\n");
        sb.append("  <summary>").append(escape(summary)).append("</summary>\n");

        if (result != null && !result.isEmpty()) {
            sb.append("  <result>").append(escape(result)).append("</result>\n");
        }

        sb.append("  <usage>\n");
        sb.append("    <total_tokens>").append(totalTokens).append("</total_tokens>\n");
        sb.append("    <tool_uses>").append(toolUses).append("</tool_uses>\n");
        sb.append("    <duration_ms>").append(durationMs).append("</duration_ms>\n");
        sb.append("  </usage>\n");
        sb.append("</task-notification>");

        return sb.toString();
    }

    /** 简化版（不含 usage 统计）. */
    public static String toXml(String taskId, String status, String summary, String result) {
        return toXml(taskId, status, summary, result, 0, 0, 0);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
