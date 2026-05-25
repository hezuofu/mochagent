// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.orchestration;

import io.sketch.mochaagents.storage.EmbeddedStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * Kanban board — backed by H2 embedded database.
 *
 * @author lanxia39@163.com
 */
public class KanbanBoard {

    private static final Logger log = LoggerFactory.getLogger(KanbanBoard.class);
    private final EmbeddedStore store;

    public enum Status { TODO, IN_PROGRESS, BLOCKED, DONE }

    public record Card(String id, String title, String assignee, String parentId,
                       Status status, Instant createdAt, Instant updatedAt) {}

    public KanbanBoard(EmbeddedStore store) {
        this.store = store;
        store.execute("""
            CREATE TABLE IF NOT EXISTS kanban_cards (
                id VARCHAR(16) PRIMARY KEY, title VARCHAR(500), assignee VARCHAR(100),
                parent_id VARCHAR(16), status VARCHAR(20) DEFAULT 'TODO',
                created_at TIMESTAMP, updated_at TIMESTAMP
            )""");
    }

    public static KanbanBoard open(EmbeddedStore store) { return new KanbanBoard(store); }

    public Card create(String title, String assignee, String parentId) {
        String id = "k" + UUID.randomUUID().toString().substring(0, 8);
        var now = Instant.now();
        store.execute("INSERT INTO kanban_cards VALUES (?,?,?,?,?,?,?)",
                id, title, assignee, parentId, "TODO",
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        return new Card(id, title, assignee, parentId, Status.TODO, now, now);
    }

    public Optional<Card> nextPending() {
        var rows = store.query("SELECT * FROM kanban_cards WHERE status='TODO' LIMIT 1");
        return rows.isEmpty() ? Optional.empty() : Optional.of(fromRow(rows.get(0)));
    }

    public void assign(String cardId, String worker) {
        store.execute("UPDATE kanban_cards SET status='IN_PROGRESS', assignee=?, updated_at=? WHERE id=?",
                worker, java.sql.Timestamp.from(Instant.now()), cardId);
    }

    public void heartbeat(String cardId) {
        store.execute("UPDATE kanban_cards SET updated_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now()), cardId);
    }

    public void block(String cardId) {
        store.execute("UPDATE kanban_cards SET status='BLOCKED', updated_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now()), cardId);
    }

    public void complete(String cardId) {
        store.execute("UPDATE kanban_cards SET status='DONE', updated_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now()), cardId);
    }

    public List<Card> children(String parentId) {
        return store.query("SELECT * FROM kanban_cards WHERE parent_id=?", parentId)
                .stream().map(this::fromRow).toList();
    }

    @SuppressWarnings("unchecked")
    private Card fromRow(Map<String, Object> row) {
        return new Card(
                (String) row.get("ID"), (String) row.get("TITLE"),
                (String) row.get("ASSIGNEE"), (String) row.get("PARENT_ID"),
                Status.valueOf((String) row.get("STATUS")),
                ((java.sql.Timestamp) row.get("CREATED_AT")).toInstant(),
                ((java.sql.Timestamp) row.get("UPDATED_AT")).toInstant());
    }
}
