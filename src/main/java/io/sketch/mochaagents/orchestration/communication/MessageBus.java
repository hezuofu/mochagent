package io.sketch.mochaagents.orchestration.communication;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 消息总线 — Agent 间的异步消息传递中枢，支持发布-订阅模式.
 * @author lanxia39@163.com
 */
public class MessageBus {

    private static final Logger log = LoggerFactory.getLogger(MessageBus.class);

    private final Map<String, List<Consumer<AgentMessage>>> subscribers = new ConcurrentHashMap<>();
    private final BlockingQueue<AgentMessage> messageQueue = new LinkedBlockingQueue<>();
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;

    /** 启动消息总线 */
    public void start() {
        running = true;
        dispatcher.submit(this::dispatchLoop);
        log.info("MessageBus started");
    }

    /** 停止消息总线 */
    public void stop() {
        running = false;
        dispatcher.shutdown();
        log.info("MessageBus stopped");
    }

    /** 发布消息 */
    public void publish(AgentMessage message) {
        messageQueue.add(message);
        log.debug("MessageBus published: {} -> {}", message.senderId(), message.receiverId());
    }

    /** 订阅消息 */
    public void subscribe(String agentId, Consumer<AgentMessage> handler) {
        subscribers.computeIfAbsent(agentId, k -> new CopyOnWriteArrayList<>()).add(handler);
        log.debug("MessageBus subscribed: {}", agentId);
    }

    /** 取消订阅 */
    public void unsubscribe(String agentId) {
        subscribers.remove(agentId);
        log.debug("MessageBus unsubscribed: {}", agentId);
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
