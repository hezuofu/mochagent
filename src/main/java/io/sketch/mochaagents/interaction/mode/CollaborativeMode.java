package io.sketch.mochaagents.interaction.mode;

import io.sketch.mochaagents.interaction.InteractionMode;
import io.sketch.mochaagents.interaction.Interactor;
import io.sketch.mochaagents.interaction.Permission;
import io.sketch.mochaagents.interaction.permission.PermissionLevel;
import io.sketch.mochaagents.interaction.permission.PermissionRequest;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 协作模式 — Agent 与用户协作，高风险操作需要用户确认.
 * @author lanxia39@163.com
 */
public class CollaborativeMode implements Interactor {

    private InteractionMode mode = InteractionMode.COLLABORATIVE;
    private final Consumer<String> output;
    private final Supplier<String> input;

    public CollaborativeMode(Consumer<String> output, Supplier<String> input) {
        this.output = output;
        this.input = input;
    }

    @Override public void send(String message) { output.accept(message); }
    @Override public String receive() { return input.get(); }
    @Override public String ask(String prompt) { output.accept(prompt); return input.get(); }
    @Override public boolean confirm(String prompt) {
        output.accept(prompt + " (y/n)");
        return "y".equalsIgnoreCase(input.get());
    }
    @Override public InteractionMode getMode() { return mode; }
    @Override public void setMode(InteractionMode mode) { this.mode = mode; }
    @Override public void showProgress(String message, double progress) {
        output.accept(String.format("[%.0f%%] %s", progress * 100, message));
    }
    @Override public void showError(String error) { output.accept("[ERROR] " + error); }

    /** 高风险操作需确认，低风险自动放行 */
    public Permission checkPermission(PermissionRequest request) {
        if (request.level().ordinal() <= PermissionLevel.MEDIUM.ordinal()) {
            return Permission.grant(request.level());
        }
        boolean approved = confirm("Allow: " + request.action() + "?");
        return approved ? Permission.grant(request.level())
                        : Permission.deny(request.level(), "User declined");
    }
}
