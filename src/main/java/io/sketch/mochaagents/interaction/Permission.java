package io.sketch.mochaagents.interaction;

import io.sketch.mochaagents.interaction.permission.PermissionLevel;
import io.sketch.mochaagents.interaction.permission.PermissionRequest;

/**
 * 权限控制 — Agent 操作权限的授权与管控.
 */
public class Permission {

    private final PermissionLevel level;
    private final boolean granted;
    private final String reason;

    public Permission(PermissionLevel level, boolean granted, String reason) {
        this.level = level;
        this.granted = granted;
        this.reason = reason;
    }

    public PermissionLevel level() { return level; }
    public boolean isGranted() { return granted; }
    public String reason() { return reason; }

    public static Permission grant(PermissionLevel level) {
        return new Permission(level, true, "Granted");
    }

    public static Permission deny(PermissionLevel level, String reason) {
        return new Permission(level, false, reason);
    }

    public static Permission fromRequest(PermissionRequest request) {
        return new Permission(request.level(), request.level().ordinal() <= PermissionLevel.MEDIUM.ordinal(),
                "Auto-approved: " + request.action());
    }
}
