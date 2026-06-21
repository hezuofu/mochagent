// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * JSONL-backed persistent MemoryStore — default in MemoryManager.create().
 *
 * <p>Stores records at {@code ~/.mocha/memory/records.jsonl}, one JSON object per line.
 * Thread-safe via ReadWriteLock — reads are concurrent, writes are serialized.
 *
 * @author lanxia39@163.com
 */
public class JsonlMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(JsonlMemoryStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final Map<String, MemoryRecord> index = new ConcurrentHashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public JsonlMemoryStore() {
        this(Paths.get(System.getProperty("user.home"), ".mocha", "memory", "records.jsonl"));
    }

    public JsonlMemoryStore(Path file) {
        this.file = file;
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) loadAll();
        } catch (IOException e) {
            log.warn("Cannot create memory store directory: {}", e.getMessage());
        }
    }

    private void loadAll() throws IOException {
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                MemoryEntry entry = JSON.readValue(line, MemoryEntry.class);
                index.put(entry.id(), entry);
            } catch (Exception e) { log.warn("Skip corrupted memory line: {}", e.getMessage()); }
        }
    }

    @Override
    public void store(MemoryRecord memory) {
        index.put(memory.id(), memory);
        persist();
    }

    @Override
    public Optional<MemoryRecord> get(String id) {
        MemoryRecord r = index.get(id);
        if (r != null) {
            r.touch();
        }
        return Optional.ofNullable(r);
    }

    @Override
    public void forget(String id) {
        index.remove(id);
        persist();
    }

    @Override
    public void clear(String type) {
        index.values().removeIf(r -> type.equals(r.type()));
        persist();
    }

    @Override
    public int size() { return index.size(); }

    @Override
    public List<MemoryRecord> search(String query) {
        String lower = query.toLowerCase();
        return index.values().stream()
                .filter(r -> r.content().toLowerCase().contains(lower)
                        || r.tags().stream().anyMatch(t -> t.toLowerCase().contains(lower))
                        || r.concepts().stream().anyMatch(c -> c.toLowerCase().contains(lower)))
                .sorted(Comparator.comparingDouble(MemoryRecord::importance).reversed())
                .toList();
    }

    @Override
    public List<MemoryRecord> getByType(String type) {
        return index.values().stream().filter(r -> type.equals(r.type())).toList();
    }

    @Override
    public List<MemoryRecord> searchByTag(String tag) {
        return index.values().stream()
                .filter(r -> r.tags().contains(tag))
                .sorted(Comparator.comparingDouble(MemoryRecord::importance).reversed())
                .toList();
    }

    @Override
    public Stream<MemoryRecord> entries() { return index.values().stream(); }

    private void persist() {
        lock.writeLock().lock();
        try {
            StringBuilder sb = new StringBuilder();
            for (var r : index.values()) {
                sb.append(JSON.writeValueAsString(r)).append("\n");
            }
            Files.writeString(file, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("Failed to persist memory store: {}", e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }
}
