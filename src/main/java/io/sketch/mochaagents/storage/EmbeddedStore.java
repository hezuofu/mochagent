// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.storage;

import org.h2.jdbcx.JdbcConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Function;

/**
 * H2 embedded database — session, memory, kanban, and plugin storage.
 *
 * <pre>{@code
 * EmbeddedStore store = EmbeddedStore.open(Path.of(".mocha/data"));
 * store.execute("CREATE TABLE IF NOT EXISTS ...");
 * store.query("SELECT * FROM sessions WHERE id=?", stmt -> stmt.setString(1, id));
 * }</pre>
 *
 * @author lanxia39@163.com
 */
public class EmbeddedStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedStore.class);

    private final JdbcConnectionPool pool;
    private final Path dataDir;

    private EmbeddedStore(Path dir) {
        this.dataDir = dir;
        try { Files.createDirectories(dir); } catch (Exception e) { /* ok */ }
        String url = "jdbc:h2:" + dir.resolve("mocha").toAbsolutePath()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE";
        this.pool = JdbcConnectionPool.create(url, "sa", "");
        this.pool.setMaxConnections(10);
    }

    public static EmbeddedStore open(Path dir) {
        return new EmbeddedStore(dir);
    }

    public static EmbeddedStore open() {
        return open(Path.of(System.getProperty("user.home"), ".mocha", "data"));
    }

    // ── DDL ──

    /** Execute DDL/SQL with no result. */
    public void execute(String sql, Object... params) {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.execute();
        } catch (SQLException e) { log.warn("Execute failed: {}", e.getMessage()); }
    }

    // ── Query ──

    /** Query with prepared statement binder. */
    public List<Map<String, Object>> query(String sql, ThrowingConsumer<PreparedStatement> binder) {
        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            binder.accept(ps);
            try (ResultSet rs = ps.executeQuery()) {
                var meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++)
                        row.put(meta.getColumnName(i), rs.getObject(i));
                    results.add(row);
                }
            }
        } catch (SQLException e) { log.warn("Query failed: {}", e.getMessage()); }
        return results;
    }

    /** Simple query with params. */
    public List<Map<String, Object>> query(String sql, Object... params) {
        return query(sql, ps -> {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
        });
    }

    /** Query and map to objects. */
    public <T> List<T> queryMap(String sql, Function<ResultSet, T> mapper, Object... params) {
        List<T> results = new ArrayList<>();
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapper.apply(rs));
            }
        } catch (SQLException e) { log.warn("QueryMap failed: {}", e.getMessage()); }
        return results;
    }

    /** Transaction with auto-commit disabled. */
    public void transaction(ThrowingConsumer<Connection> work) {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try { work.accept(c); c.commit(); }
            catch (Exception e) { c.rollback(); throw e; }
        } catch (Exception e) { log.warn("Transaction failed: {}", e.getMessage()); }
    }

    public Path dataDir() { return dataDir; }

    @Override
    public void close() { pool.dispose(); }

    @FunctionalInterface
    public interface ThrowingConsumer<T> {
        void accept(T t) throws SQLException;
    }
}
