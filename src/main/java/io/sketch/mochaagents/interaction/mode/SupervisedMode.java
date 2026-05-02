package io.sketch.mochaagents.interaction.mode;

import io.sketch.mochaagents.interaction.InteractionMode;
import io.sketch.mochaagents.interaction.Interactor;
import io.sketch.mochaagents.interaction.Permission;
import io.sketch.mochaagents.interaction.permission.PermissionRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 监督模式 — 所有关键操作需人工审核批准，提供完整操作日志.
 */
public class SupervisedMode implements Interactor {

    private InteractionMode mode = InteractionMode.SUPERVISED;
    private final Consumer<String> output;
    private final Supplier<String> input;
    private final List<String> auditLog = new ArrayList<>();

    public SupervisedMode(Consumer<String> output, Supplier<String> input) {
        this.output = output;
        this.input = input;
    }

    @Override public void send(String message) { log("SEND", message); output.accept(message); }
    @Override public String receive() { String r = input.get(); log("RECEIVE", r); return r; }
    @Override public String ask(String prompt) { log("ASK", prompt); output.accept(prompt); return input.get(); }
    @Override public boolean confirm(String prompt) {
        log("CONFIRM", prompt);
        output.accept("[CONFIRM] " + prompt + " (y/n)");
        return "y".equalsIgnoreCase(input.get());
    }
    @Override public InteractionMode getMode() { return mode; }
    @Override public void setMode(InteractionMode mode) { this.mode = mode; }
    @Override public void showProgress(String message, double progress) {
        output.accept(String.format("[%.0f%%] %s", progress * 100, message));
    }
    @Override public void showError(String error) { log("ERROR", error); output.accept("[ERROR] " + error); }

    /** 所有操作必须批准 */
    public Permission requireApproval(PermissionRequest request) {
        boolean approved = confirm("Approve action [" + request.level() + "]: " + request.action() + "?");
        log("PERMISSION", request.action() + " -> " + (approved ? "GRANTED" : "DENIED"));
        return approved ? Permission.grant(request.level())
                        : Permission.deny(request.level(), "Supervisor declined");
    }

    /** 获取审计日志 */
    public List<String> getAuditLog() { return List.copyOf(auditLog); }

    private void log(String type, String message) {
        auditLog.add(String.format("[%s] %s: %s", java.time.Instant.now(), type, message));
    }
}
