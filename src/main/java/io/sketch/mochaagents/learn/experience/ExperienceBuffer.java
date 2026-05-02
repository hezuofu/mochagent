package io.sketch.mochaagents.learn.experience;

import io.sketch.mochaagents.learn.Experience;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 经验缓冲区 — 环形缓冲区存储最近的经验，支持优先级采样.
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public class ExperienceBuffer<I, O> {

    private final int capacity;
    private final Deque<Experience<I, O>> buffer;

    public ExperienceBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = new ConcurrentLinkedDeque<>();
    }

    public ExperienceBuffer() {
        this(1000);
    }

    /** 添加经验 */
    public void add(Experience<I, O> experience) {
        buffer.addLast(experience);
        while (buffer.size() > capacity) {
            buffer.removeFirst();
        }
    }

    /** 批量添加 */
    public void addAll(Collection<Experience<I, O>> experiences) {
        experiences.forEach(this::add);
    }

    /** 随机采样 */
    public List<Experience<I, O>> sample(int n) {
        List<Experience<I, O>> all = new ArrayList<>(buffer);
        Collections.shuffle(all);
        return all.subList(0, Math.min(n, all.size()));
    }

    /** 基于奖励的优先级采样 (正样本更可能被选中) */
    public List<Experience<I, O>> prioritizedSample(int n) {
        List<Experience<I, O>> all = new ArrayList<>(buffer);
        all.sort(Comparator.<Experience<I, O>>comparingDouble(e -> e.reward()).reversed());
        return all.subList(0, Math.min(n, all.size()));
    }

    /** 获取最近的 N 条经验 */
    public List<Experience<I, O>> recent(int n) {
        List<Experience<I, O>> all = new ArrayList<>(buffer);
        int start = Math.max(0, all.size() - n);
        return all.subList(start, all.size());
    }

    /** 获取正向经验 */
    public List<Experience<I, O>> positive() {
        return buffer.stream().filter(Experience::isPositive).toList();
    }

    /** 缓冲区大小 */
    public int size() { return buffer.size(); }

    /** 是否已满 */
    public boolean isFull() { return buffer.size() >= capacity; }

    /** 清空缓冲区 */
    public void clear() { buffer.clear(); }
}
