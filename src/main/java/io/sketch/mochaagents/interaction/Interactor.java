package io.sketch.mochaagents.interaction;

/**
 * 交互器接口 — Agent 与外部世界（用户、系统、其他 Agent）的交互通道.
 * @author lanxia39@163.com
 */
public interface Interactor {

    /** 发送消息 */
    void send(String message);

    /** 接收消息（阻塞） */
    String receive();

    /** 请求用户输入 */
    String ask(String prompt);

    /** 请求用户确认 */
    boolean confirm(String prompt);

    /** 获取当前交互模式 */
    InteractionMode getMode();

    /** 切换交互模式 */
    void setMode(InteractionMode mode);

    /** 显示进度 */
    void showProgress(String message, double progress);

    /** 显示错误 */
    void showError(String error);
}
