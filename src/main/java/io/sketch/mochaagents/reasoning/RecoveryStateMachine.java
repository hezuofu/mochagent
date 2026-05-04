package io.sketch.mochaagents.reasoning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Recovery state machine — replicates claude-code's multi-stage recovery chain.
 *
 * <p>When the model hits a limit, the system escalates through increasingly
 * expensive recovery strategies:
 *
 * <pre>
 *   NORMAL → max_output_tokens → ESCALATE_TOKENS (64k) → RESUME_INJECT (3x)
 *          → prompt_too_long → COLLAPSE_DRAIN → REACTIVE_COMPACT → GIVE_UP
 *          → api_error → MODEL_FALLBACK → GIVE_UP
 * </pre>
 *
 * <p>Each recovery attempt is tracked and logged. After too many failures,
 * the machine gives up and returns the best available result.
 *
 * @author lanxia39@163.com
 */
public class RecoveryStateMachine {

    private static final Logger log = LoggerFactory.getLogger(RecoveryStateMachine.class);

    public enum Phase {
        NORMAL,
        MAX_OUTPUT_TOKENS_RECOVERY,
        ESCALATED_TOKENS,
        RESUME_INJECT,
        PROMPT_TOO_LONG,
        COLLAPSE_DRAIN,
        REACTIVE_COMPACT,
        MODEL_FALLBACK,
        GIVE_UP
    }

    public enum Transition {
        NEXT_TURN,
        RECOVERY_RETRY,
        COMPLETED,
        FAILED
    }

    private Phase currentPhase = Phase.NORMAL;
    private int maxOutputTokensRecoveryCount;
    private int resumeInjectCount;
    private int reactiveCompactCount;
    private int modelFallbackCount;
    private final int maxResumeInjects;
    private final int maxReactiveCompacts;
    private final int maxModelFallbacks;
    private final int defaultMaxTokens;
    private final int escalatedMaxTokens;
    private final List<RecoveryEvent> history = new ArrayList<>();

    public RecoveryStateMachine() {
        this(4096, 64000, 3, 2, 1);
    }

    public RecoveryStateMachine(int defaultMaxTokens, int escalatedMaxTokens,
                                 int maxResumeInjects, int maxReactiveCompacts,
                                 int maxModelFallbacks) {
        this.defaultMaxTokens = defaultMaxTokens;
        this.escalatedMaxTokens = escalatedMaxTokens;
        this.maxResumeInjects = maxResumeInjects;
        this.maxReactiveCompacts = maxReactiveCompacts;
        this.maxModelFallbacks = maxModelFallbacks;
    }

    // ============ Recovery triggers ============

    /** Called when the model hits the max_output_tokens limit. */
    public RecoveryAction onMaxOutputTokens() {
        maxOutputTokensRecoveryCount++;

        if (maxOutputTokensRecoveryCount == 1) {
            // First time: escalate token budget
            transition(Phase.ESCALATED_TOKENS);
            log.info("Recovery: escalating max tokens to {}", escalatedMaxTokens);
            return RecoveryAction.overrideMaxTokens(escalatedMaxTokens);
        }

        if (resumeInjectCount < maxResumeInjects) {
            // Subsequent times: inject resume message
            resumeInjectCount++;
            transition(Phase.RESUME_INJECT);
            log.info("Recovery: injecting resume message (attempt {}/{})",
                    resumeInjectCount, maxResumeInjects);
            return RecoveryAction.injectResumeMessage(
                    "Continue — do not apologize, do not recap. "
                    + "Pick up exactly where you left off.");
        }

        transition(Phase.GIVE_UP);
        log.warn("Recovery: max resume injects exhausted, giving up");
        return RecoveryAction.giveUp("Max output token recovery exhausted");
    }

    /** Called when the API returns a prompt_too_long error. */
    public RecoveryAction onPromptTooLong() {
        if (reactiveCompactCount < maxReactiveCompacts) {
            reactiveCompactCount++;
            transition(Phase.REACTIVE_COMPACT);
            log.info("Recovery: triggering reactive compact (attempt {}/{})",
                    reactiveCompactCount, maxReactiveCompacts);
            return RecoveryAction.compactContext();
        }

        transition(Phase.GIVE_UP);
        log.warn("Recovery: max reactive compacts exhausted");
        return RecoveryAction.giveUp("Context too long after max compactions");
    }

    /** Called when the primary model API fails. */
    public RecoveryAction onModelError(String modelName, int statusCode) {
        if (modelFallbackCount < maxModelFallbacks) {
            modelFallbackCount++;
            transition(Phase.MODEL_FALLBACK);
            log.info("Recovery: falling back from {} after status={} (attempt {}/{})",
                    modelName, statusCode, modelFallbackCount, maxModelFallbacks);
            return RecoveryAction.fallbackModel();
        }

        transition(Phase.GIVE_UP);
        log.warn("Recovery: max model fallbacks exhausted for {}", modelName);
        return RecoveryAction.giveUp("Model error after max fallbacks: " + statusCode);
    }

    /** Called after a successful turn to reset recovery counters. */
    public void onSuccessfulTurn() {
        if (maxOutputTokensRecoveryCount > 0 || resumeInjectCount > 0) {
            log.debug("Recovery: resetting after successful turn");
            maxOutputTokensRecoveryCount = 0;
            resumeInjectCount = 0;
        }
        transition(Phase.NORMAL);
    }

    /** Called to start a new turn. */
    public Transition beginTurn() {
        recordEvent(Phase.NORMAL, "begin_turn");
        return Transition.NEXT_TURN;
    }

    /** Called when the turn completes successfully. */
    public Transition completeTurn() {
        onSuccessfulTurn();
        recordEvent(Phase.NORMAL, "turn_completed");
        return Transition.COMPLETED;
    }

    // ============ State queries ============

    public Phase currentPhase() { return currentPhase; }
    public boolean isInRecovery() { return currentPhase != Phase.NORMAL; }
    public int totalRecoveries() {
        return maxOutputTokensRecoveryCount + reactiveCompactCount + modelFallbackCount;
    }
    public List<RecoveryEvent> history() { return List.copyOf(history); }

    /** Summary for logging/telemetry. */
    public String summary() {
        return String.format("Recovery[phase=%s, maxTokRcv=%d, resumeInj=%d, compact=%d, fallback=%d]",
                currentPhase, maxOutputTokensRecoveryCount, resumeInjectCount,
                reactiveCompactCount, modelFallbackCount);
    }

    // ============ Internal ============

    private void transition(Phase newPhase) {
        Phase old = currentPhase;
        currentPhase = newPhase;
        recordEvent(newPhase, old + "→" + newPhase);
    }

    private void recordEvent(Phase phase, String detail) {
        history.add(new RecoveryEvent(phase, detail, System.currentTimeMillis()));
        if (history.size() > 100) history.remove(0);
    }

    // ============ Types ============

    public record RecoveryEvent(Phase phase, String detail, long timestampMs) {}

    /**
     * Action returned by recovery triggers — tells the caller what to do.
     */
    public sealed interface RecoveryAction
            permits RecoveryAction.OverrideTokens, RecoveryAction.InjectMessage,
                    RecoveryAction.Compact, RecoveryAction.Fallback, RecoveryAction.Terminate {

        /** Override max output tokens for the next API call. */
        record OverrideTokens(int newMaxTokens) implements RecoveryAction {}
        /** Inject a resume message into the conversation. */
        record InjectMessage(String message) implements RecoveryAction {}
        /** Trigger context compaction. */
        record Compact() implements RecoveryAction {}
        /** Fall back to a different model. */
        record Fallback() implements RecoveryAction {}
        /** Give up — return best available result. */
        record Terminate(String reason) implements RecoveryAction {}

        static RecoveryAction overrideMaxTokens(int tokens) { return new OverrideTokens(tokens); }
        static RecoveryAction injectResumeMessage(String msg) { return new InjectMessage(msg); }
        static RecoveryAction compactContext() { return new Compact(); }
        static RecoveryAction fallbackModel() { return new Fallback(); }
        static RecoveryAction giveUp(String reason) { return new Terminate(reason); }
    }
}
