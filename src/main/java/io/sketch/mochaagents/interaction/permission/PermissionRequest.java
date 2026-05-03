package io.sketch.mochaagents.interaction.permission;

import java.time.Instant;
import java.util.UUID;

/**
 * 权限请求 — 描述一次操作权限申请.
 * @author lanxia39@163.com
 */
public class PermissionRequest {

    private final String id;
    private final String action;
    private final PermissionLevel level;
    private final String description;
    private final Instant timestamp;

    public PermissionRequest(String action, PermissionLevel level, String description) {
        this.id = UUID.randomUUID().toString();
        this.action = action;
        this.level = level;
        this.description = description;
        this.timestamp = Instant.now();
    }

    public String id() { return id; }
    public String action() { return action; }
    public PermissionLevel level() { return level; }
    public String description() { return description; }
    public Instant timestamp() { return timestamp; }

    public static PermissionRequest read(String action) {
        return new PermissionRequest(action, PermissionLevel.READ, "Read-only: " + action);
    }

    public static PermissionRequest write(String action) {
        return new PermissionRequest(action, PermissionLevel.LOW, "Write: " + action);
    }

    public static PermissionRequest dangerous(String action) {
        return new PermissionRequest(action, PermissionLevel.HIGH, "Dangerous: " + action);
    }

    public static PermissionRequest critical(String action) {
        return new PermissionRequest(action, PermissionLevel.CRITICAL, "Critical: " + action);
    }
}
