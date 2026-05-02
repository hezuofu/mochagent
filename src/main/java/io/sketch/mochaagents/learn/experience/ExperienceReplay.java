package io.sketch.mochaagents.learn.experience;

import io.sketch.mochaagents.learn.Experience;
import java.util.*;

/**
 * 经验回放 — 从历史经验中随机采样进行离线学习，打破时序相关性.
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public class ExperienceReplay<I, O> {

    private final ExperienceBuffer<I, O> buffer;
    private final int batchSize;

    public ExperienceReplay(int bufferCapacity, int batchSize) {
        this.buffer = new ExperienceBuffer<>(bufferCapacity);
        this.batchSize = batchSize;
    }

    public ExperienceReplay() {
        this(10000, 32);
    }

    /** 存储经验 */
    public void store(Experience<I, O> experience) {
        buffer.add(experience);
    }

    /** 批量存储 */
    public void storeBatch(Collection<Experience<I, O>> experiences) {
        buffer.addAll(experiences);
    }

    /** 随机采样一个批次 */
    public List<Experience<I, O>> sampleBatch() {
        return buffer.sample(batchSize);
    }

    /** 优先采样一个批次（偏好高奖励经验） */
    public List<Experience<I, O>> samplePrioritizedBatch() {
        return buffer.prioritizedSample(batchSize);
    }

    /** 经验池大小 */
    public int size() { return buffer.size(); }

    /** 是否可以采样一个完整批次 */
    public boolean canSample() { return buffer.size() >= batchSize; }

    /** 清空经验池 */
    public void clear() { buffer.clear(); }
}
