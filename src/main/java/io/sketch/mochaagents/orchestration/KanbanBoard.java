// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.orchestration;
import java.nio.file.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kanban board — hermes-agent SQLite-based cross-process task distribution.
 *
 * <pre>{@code
 * KanbanBoard board = KanbanBoard.open();
 * board.create("Review login module", "reviewer", null);
 * board.assign(board.nextPending(), "worker-1");
 * board.heartbeat("task-1", "Working on it...");
 * board.complete("task-1", "All tests pass", Map.of("files", 3));
 * }</pre>
 *
 * @author lanxia39@163.com
 */
public class KanbanBoard implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KanbanBoard.class);
    private final Connection db;
    private final Map<String, Card> cache = new ConcurrentHashMap<>();

    public enum Status { TODO, IN_PROGRESS, BLOCKED, DONE }

    public record Card(String id, String title, String assignee, String parentId,
                       Status status, String summary, Map<String, Object> metadata,
                       Instant createdAt, Instant updatedAt) {}

    private KanbanBoard(Connection db) { this.db = db; initSchema(); }

    public static KanbanBoard open() {
        try {
            Path path = Path.of(System.getProperty("kanban.db", ".mocha/kanban.db"));
            Files.createDirectories(path.getParent());
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            return new KanbanBoard(conn);
        } catch (Exception e) {
            log.error("Failed to open Kanban DB: {}", e.getMessage());
            return null;
        }
    }

    private void initSchema() {
        try (Statement s = db.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS cards ("
                    + "id TEXT PRIMARY KEY, title TEXT, assignee TEXT, parent_id TEXT,"
                    + "status TEXT DEFAULT 'TODO', summary TEXT, metadata TEXT,"
                    + "created_at TEXT, updated_at TEXT)");
        } catch (SQLException e) { log.error("Schema init failed", e); }
    }

    public Card create(String title, String assignee, String parentId) {
        String id = "k" + UUID.randomUUID().toString().substring(0, 8);
        Card card = new Card(id, title, assignee, parentId, Status.TODO, null,
                Map.of(), Instant.now(), Instant.now());
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO cards VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, title); ps.setString(3, assignee);
            ps.setString(4, parentId); ps.setString(5, "TODO"); ps.setString(6, null);
            ps.setString(7, "{}"); ps.setString(8, Instant.now().toString());
            ps.setString(9, Instant.now().toString());
            ps.executeUpdate();
            cache.put(id, card);
        } catch (SQLException e) { log.error("Create failed", e); }
        return card;
    }

    public Card nextPending() {
        try (Statement s = db.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM cards WHERE status='TODO' LIMIT 1")) {
            if (rs.next()) return fromRow(rs);
        } catch (SQLException e) { log.error("Query failed", e); }
        return null;
    }

    public Card assign(String cardId, String worker) {
        return updateStatus(cardId, Status.IN_PROGRESS, worker);
    }

    public Card heartbeat(String cardId, String note) {
        Card c = cache.get(cardId);
        if (c != null) {
            try (PreparedStatement ps = db.prepareStatement(
                    "UPDATE cards SET updated_at=? WHERE id=?")) {
                ps.setString(1, Instant.now().toString()); ps.setString(2, cardId);
                ps.executeUpdate();
            } catch (SQLException e) { /* best-effort */ }
        }
        return c;
    }

    public Card block(String cardId, String reason) {
        return update(cardId, Status.BLOCKED, null, reason);
    }

    public Card complete(String cardId, String summary, Map<String, Object> metadata) {
        return update(cardId, Status.DONE, summary, null);
    }

    public List<Card> children(String parentId) {
        List<Card> result = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT * FROM cards WHERE parent_id=?")) {
            ps.setString(1, parentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(fromRow(rs));
            }
        } catch (SQLException e) { log.error("Query failed", e); }
        return result;
    }

    private Card updateStatus(String id, Status status, String assignee) {
        return update(id, status, null, null);
    }

    private Card update(String id, Status status, String summary, String assignee) {
        try (PreparedStatement ps = db.prepareStatement(
                "UPDATE cards SET status=?,summary=?,updated_at=? WHERE id=?")) {
            ps.setString(1, status.name()); ps.setString(2, summary);
            ps.setString(3, Instant.now().toString()); ps.setString(4, id);
            ps.executeUpdate();
        } catch (SQLException e) { log.error("Update failed", e); }
        return cache.remove(id);
    }

    private Card fromRow(ResultSet rs) throws SQLException {
        var meta = new HashMap<String, Object>();
        return new Card(rs.getString("id"), rs.getString("title"),
                rs.getString("assignee"), rs.getString("parent_id"),
                Status.valueOf(rs.getString("status")), rs.getString("summary"),
                meta, Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    @Override public void close() {
        try { db.close(); } catch (SQLException e) { /* ignore */ }
    }
}
