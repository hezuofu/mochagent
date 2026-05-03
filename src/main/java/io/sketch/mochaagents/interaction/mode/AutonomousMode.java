package io.sketch.mochaagents.interaction.mode;

import io.sketch.mochaagents.interaction.InteractionMode;
import io.sketch.mochaagents.interaction.Interactor;
import io.sketch.mochaagents.interaction.Permission;
import io.sketch.mochaagents.interaction.permission.PermissionRequest;

/**
 * 自主模式 — Agent 全自动运行，所有操作自动批准.
 * @author lanxia39@163.com
 */
public class AutonomousMode implements Interactor {

    private InteractionMode mode = InteractionMode.AUTONOMOUS;

    @Override public void send(String message) { /* 自动处理 */ }
    @Override public String receive() { return ""; }
    @Override public String ask(String prompt) { return ""; }
    @Override public boolean confirm(String prompt) { return true; }
    @Override public InteractionMode getMode() { return mode; }
    @Override public void setMode(InteractionMode mode) { this.mode = mode; }
    @Override public void showProgress(String message, double progress) {}
    @Override public void showError(String error) {}

    /** 自动批准所有操作 */
    public Permission autoApprove(PermissionRequest request) {
        return Permission.grant(request.level());
    }
}
