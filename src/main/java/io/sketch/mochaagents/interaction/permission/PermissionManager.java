package io.sketch.mochaagents.interaction.permission;

import io.sketch.mochaagents.interaction.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 权限管理器 — 集中管理 Agent 操作权限的申请、审批与撤销.
 */
public class PermissionManager {

    private static final Logger log = LoggerFactory.getLogger(PermissionManager.class);

    private final Map<String, Permission> grants = new LinkedHashMap<>();
    private final List<PermissionRequest> history = new ArrayList<>();
    private PermissionLevel defaultLevel = PermissionLevel.LOW;

    /** 请求权限 */
    public Permission request(PermissionRequest request) {
        history.add(request);
        Permission permission = evaluate(request);
        grants.put(request.action(), permission);
        log.debug("Permission request: action={}, level={}, granted={}",
                request.action(), request.level(), permission.isGranted());
        return permission;
    }

    /** 检查是否已授权 */
    public boolean isGranted(String action) {
        Permission p = grants.get(action);
        return p != null && p.isGranted();
    }

    /** 撤销权限 */
    public void revoke(String action) {
        grants.remove(action);
        log.debug("Permission revoked: {}", action);
    }

    /** 设置默认权限级别 */
    public void setDefaultLevel(PermissionLevel level) {
        this.defaultLevel = level;
        log.info("Default permission level set to {}", level);
    }

    /** 获取权限历史 */
    public List<PermissionRequest> history() {
        return List.copyOf(history);
    }

    private Permission evaluate(PermissionRequest request) {
        if (request.level().ordinal() <= defaultLevel.ordinal()) {
            return Permission.grant(request.level());
        }
        return Permission.deny(request.level(), "Requires explicit approval");
    }
}
