package io.sketch.mochaagents.llm;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 流式响应 — 支持逐 token 消费的 LLM 响应流.
 * @author lanxia39@163.com
 */
public class StreamingResponse implements Iterable<String> {

    private final BlockingQueue<String> tokens = new LinkedBlockingQueue<>();
    private volatile boolean completed = false;
    private volatile Throwable error;

    /** 推送 token */
    public void push(String token) {
        tokens.add(token);
    }

    /** 标记完成 */
    public void complete() {
        this.completed = true;
    }

    /** 标记错误 */
    public void error(Throwable t) {
        this.error = t;
        this.completed = true;
    }

    /** 订阅消费 */
    public void subscribe(Consumer<String> onToken, Consumer<Throwable> onError, Runnable onComplete) {
        new Thread(() -> {
            try {
                while (!completed || !tokens.isEmpty()) {
                    String token = tokens.poll(100, TimeUnit.MILLISECONDS);
                    if (token != null) onToken.accept(token);
                }
                if (error != null) onError.accept(error);
                else onComplete.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                onError.accept(e);
            }
        }).start();
    }

    @Override
    public Iterator<String> iterator() {
        return new Iterator<>() {
            @Override public boolean hasNext() { return !completed || !tokens.isEmpty(); }
            @Override public String next() {
                try {
                    String token = tokens.poll(100, TimeUnit.MILLISECONDS);
                    if (token == null && completed) throw new NoSuchElementException();
                    return token;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new NoSuchElementException();
                }
            }
        };
    }
}
