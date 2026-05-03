package io.sketch.mochaagents.safety.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 审计日志 — 记录所有 Agent 操作，支持追溯与合规审查.
 * @author lanxia39@163.com
 */
public class AuditLogger {

    private final List<AuditEntry> entries = new ArrayList<>();

    /** 记录操作 */
    public void log(String action, String agentId, String details, AuditLevel level) {
        entries.add(new AuditEntry(Instant.now(), action, agentId, details, level));
    }

    /** 按级别筛选 */
    public List<AuditEntry> getByLevel(AuditLevel level) {
        return entries.stream().filter(e -> e.level == level).toList();
    }

    /** 获取所有记录 */
    public List<AuditEntry> getAll() { return List.copyOf(entries); }

    /** 按时间范围查询 */
    public List<AuditEntry> query(Instant from, Instant to) {
        return entries.stream()
                .filter(e -> !e.timestamp.isBefore(from) && !e.timestamp.isAfter(to))
                .toList();
    }

    public enum AuditLevel { INFO, WARNING, ERROR, CRITICAL }

    public record AuditEntry(Instant timestamp, String action, String agentId, String details, AuditLevel level) {}
}
