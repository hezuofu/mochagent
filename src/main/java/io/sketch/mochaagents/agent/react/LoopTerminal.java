package io.sketch.mochaagents.agent.react;

/**
 * Loop terminal result — tells WHY the ReAct loop stopped.
 * Pattern borrowed from claude-code's discriminated return type.
 * @author lanxia39@163.com
 */
public record LoopTerminal(Reason reason, String detail) {

    public enum Reason {
        COMPLETED,        // final_answer() called
        MAX_STEPS,        // step budget exhausted
        ERROR,            // unrecoverable error
        ABORTED,          // external abort signal
    }

    public boolean isSuccess() { return reason == Reason.COMPLETED; }

    public static LoopTerminal completed() { return new LoopTerminal(Reason.COMPLETED, null); }
    public static LoopTerminal maxSteps(int steps) { return new LoopTerminal(Reason.MAX_STEPS, "max steps: " + steps); }
    public static LoopTerminal error(String msg) { return new LoopTerminal(Reason.ERROR, msg); }
    public static LoopTerminal aborted() { return new LoopTerminal(Reason.ABORTED, null); }
}
