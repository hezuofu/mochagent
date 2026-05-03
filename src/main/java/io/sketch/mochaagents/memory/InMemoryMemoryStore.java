package io.sketch.mochaagents.memory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * {@link MemoryStore} 的内存驻留实现，基于 {@link ConcurrentHashMap}.
 *
 * <p>线程安全，进程退出后数据丢失。作为默认存储后端，
 * 由 {@link MemoryManager} 无参构造时自动创建.
 * @author lanxia39@163.com
 */
public class InMemoryMemoryStore implements MemoryStore {

    private final Map<String, Memory> store = new ConcurrentHashMap<>();

    @Override
    public void store(Memory memory) {
        store.put(memory.id(), memory);
    }

    @Override
    public Optional<Memory> get(String id) {
        Memory m = store.get(id);
        if (m != null) m.touch();
        return Optional.ofNullable(m);
    }

    @Override
    public void forget(String id) {
        store.remove(id);
    }

    @Override
    public void clear(String type) {
        store.values().removeIf(m -> m.type().equals(type));
    }

    @Override
    public int size() {
        return store.size();
    }

    @Override
    public List<Memory> search(String query) {
        String lower = query.toLowerCase();
        return sorted(store.values().stream()
                .filter(m -> m.content().toLowerCase().contains(lower)));
    }

    @Override
    public List<Memory> getByType(String type) {
        return sorted(store.values().stream()
                .filter(m -> m.type().equals(type)));
    }

    @Override
    public List<Memory> searchByTag(String tag) {
        return sorted(store.values().stream()
                .filter(m -> m.tags().contains(tag)));
    }

    @Override
    public Stream<Memory> entries() {
        return store.values().stream();
    }

    private static List<Memory> sorted(Stream<Memory> stream) {
        return stream.sorted(Comparator.comparingDouble(Memory::importance).reversed()).toList();
    }
}
