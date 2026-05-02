package io.sketch.mochaagents.orchestration.communication;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 消息总线 — Agent 间的异步消息传递中枢，支持发布-订阅模式.
 */
public class MessageBus {

    private final Map<String, List<Consumer<AgentMessage>>> subscribers = new ConcurrentHashMap<>();
    private final BlockingQueue<AgentMessage> messageQueue = new LinkedBlockingQueue<>();
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;

    /** 启动消息总线 */
    public void start() {
        running = true;
        dispatcher.submit(this::dispatchLoop);
    }

    /** 停止消息总线 */
    public void stop() {
        running = false;
        dispatcher.shutdown();
    }

    /** 发布消息 */
    public void publish(AgentMessage message) {
        messageQueue.add(message);
    }

    /** 订阅消息 */
    public void subscribe(String agentId, Consumer<AgentMessage> handler) {
        subscribers.computeIfAbsent(agentId, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /** 取消订阅 */
    public void unsubscribe(String agentId) {
        subscribers.remove(agentId);
    }

    private void dispatchLoop() {
        while (running) {
            try {
                AgentMessage msg = messageQueue.poll(100, TimeUnit.MILLISECONDS);
                if (msg != null) {
                    dispatch(msg);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void dispatch(AgentMessage msg) {
        // 点对点
        List<Consumer<AgentMessage>> targetHandlers = subscribers.get(msg.receiverId());
        if (targetHandlers != null) {
            targetHandlers.forEach(h -> h.accept(msg));
        }

        // 广播
        if ("ALL".equals(msg.receiverId())) {
            subscribers.forEach((id, handlers) -> {
                if (!id.equals(msg.senderId())) {
                    handlers.forEach(h -> h.accept(msg));
                }
            });
        }
    }
}
