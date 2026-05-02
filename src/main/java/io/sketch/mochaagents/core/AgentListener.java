package io.sketch.mochaagents.core;

/**
 * Agent 事件监听器 — 观察者模式，监听 Agent 执行生命周期事件.
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public interface AgentListener<I, O> {

    /**
     * 执行开始时回调.
     */
    default void onStart(AgentEvent<I> event) {}

    /**
     * 执行完成时回调.
     */
    default void onComplete(AgentEvent<O> event) {}

    /**
     * 执行出错时回调.
     */
    default void onError(AgentEvent<Throwable> event) {}

    /**
     * 进度更新回调.
     */
    default void onProgress(double progress) {}
}
