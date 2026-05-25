// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.storage;

import java.nio.file.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddedStoreTest {

    private EmbeddedStore store;
    private Path tmpDir;

    @BeforeEach void setup() throws Exception {
        tmpDir = Files.createTempDirectory("mocha-test");
        store = EmbeddedStore.open(tmpDir);
    }

    @AfterEach void teardown() {
        store.close();
        try { Files.walk(tmpDir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (Exception e) {} }); } catch (Exception e) {}
    }

    @Test void createsTableAndInserts() {
        store.execute("CREATE TABLE test (id INT PRIMARY KEY, name VARCHAR)");
        store.execute("INSERT INTO test VALUES (?, ?)", 1, "hello");

        var rows = store.query("SELECT * FROM test WHERE id=?", 1);
        assertEquals(1, rows.size());
        assertEquals("hello", rows.get(0).get("NAME"));
    }

    @Test void queryReturnsEmptyForNoMatch() {
        store.execute("CREATE TABLE test (id INT PRIMARY KEY)");
        var rows = store.query("SELECT * FROM test WHERE id=?", 999);
        assertTrue(rows.isEmpty());
    }

    @Test void transactionCommits() {
        store.execute("CREATE TABLE test (id INT PRIMARY KEY, val INT)");
        store.transaction(conn -> {
            try (var ps = conn.prepareStatement("INSERT INTO test VALUES (?, ?)")) {
                ps.setInt(1, 1); ps.setInt(2, 100); ps.executeUpdate();
            }
        });
        var rows = store.query("SELECT * FROM test WHERE id=?", 1);
        assertEquals(100, rows.get(0).get("VAL"));
    }

    @Test void transactionRollsBackOnError() {
        store.execute("CREATE TABLE test (id INT PRIMARY KEY)");
        try {
            store.transaction(conn -> {
                conn.prepareStatement("INSERT INTO test VALUES (?, ?)").executeUpdate();
                throw new RuntimeException("boom");
            });
        } catch (Exception ignored) {}
        assertTrue(store.query("SELECT * FROM test").isEmpty());
    }
}
