package io.sketch.mochaagents.memory;

/**
 * Session-level working memory — key info and SOP references for the current task.
 *
 * <p>Pattern from GenericAgent's do_update_working_checkpoint().
 * Injected into every turn via the anchor prompt.
 */
public class WorkingMemory {
    private String keyInfo = "";
    private String relatedSop = "";
    private int passedSessions;

    public void update(String keyInfo, String relatedSop) {
        if (keyInfo != null && !keyInfo.isBlank()) this.keyInfo = keyInfo;
        if (relatedSop != null && !relatedSop.isBlank()) this.relatedSop = relatedSop;
        this.passedSessions = 0;
    }

    public String keyInfo() { return keyInfo; }
    public String relatedSop() { return relatedSop; }
    public int passedSessions() { return passedSessions; }
    public void incrementPassedSessions() { passedSessions++; }
    public void reset() { keyInfo = ""; relatedSop = ""; passedSessions = 0; }

    public String toPrompt() {
        if (keyInfo.isEmpty() && relatedSop.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n## Working Memory\n");
        if (!keyInfo.isEmpty()) sb.append("<key_info>").append(keyInfo).append("</key_info>\n");
        if (!relatedSop.isEmpty()) sb.append("Check ").append(relatedSop).append(" if unclear.\n");
        return sb.toString();
    }
}
