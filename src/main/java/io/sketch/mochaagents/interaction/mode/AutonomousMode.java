package io.sketch.mochaagents.interaction.mode;

import io.sketch.mochaagents.interaction.InteractionMode;
import io.sketch.mochaagents.interaction.Interactor;
import io.sketch.mochaagents.interaction.Permission;
import io.sketch.mochaagents.interaction.permission.PermissionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 自主模式 — Agent 全自动运行，通过消息队列接收外部输入，所有操作自动批准.
 * @author lanxia39@163.com
 */
public class AutonomousMode implements Interactor {

    private static final Logger log = LoggerFactory.getLogger(AutonomousMode.class);
    private final Queue<String> incomingMessages = new ConcurrentLinkedQueue<>();
    private InteractionMode mode = InteractionMode.AUTONOMOUS;

    @Override
    public void send(String message) {
        log.debug("[Autonomous] output: {}", truncate(message, 120));
    }

    @Override
    public String receive() {
        return incomingMessages.poll(); // null if no pending messages
    }

    @Override
    public String ask(String prompt) {
        log.debug("[Autonomous] question: {}", truncate(prompt, 120));
        return null; // no user to answer, agent must self-resolve
    }

    @Override
    public boolean confirm(String prompt) {
        log.debug("[Autonomous] auto-confirmed: {}", truncate(prompt, 80));
        return true;
    }

    @Override
    public InteractionMode getMode() { return mode; }
    @Override
    public void setMode(InteractionMode mode) { this.mode = mode; }

    @Override
    public void showProgress(String message, double progress) {
        log.info("[Autonomous] progress {}%: {}", String.format("%.0f", progress * 100),
                truncate(message, 80));
    }

    @Override
    public void showError(String error) {
        log.warn("[Autonomous] error: {}", error);
    }

    /** External callers push messages for the agent to receive. */
    public void pushMessage(String message) {
        incomingMessages.add(message);
    }

    /** Auto-approve all operations based on risk level. */
    public Permission autoApprove(PermissionRequest request) {
        Permission perm = Permission.grant(request.level());
        log.debug("[Autonomous] auto-approved: level={}", request.level());
        return perm;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
