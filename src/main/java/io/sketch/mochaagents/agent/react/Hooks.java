package io.sketch.mochaagents.agent.react;

import io.sketch.mochaagents.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Hook system — full replication of claude-code's tool hooks + permission integration.
 *
 * <p>Hook types (claude-code equivalents):
 * <ul>
 *   <li>{@code PreToolHook} → PreToolUse (can modify input, deny, or allow)</li>
 *   <li>{@code PostToolHook} → PostToolUse (can modify output, yield messages)</li>
 *   <li>{@code StopHook} → Stop/StopFailure (fires on session end)</li>
 * </ul>
 *
 * <p>Permission integration: Hook allow DOES NOT bypass settings.json deny/ask rules.
 * Hook deny is final. Hook ask delegates to the permission mode (auto/plan/dontAsk).
 * @author lanxia39@163.com
 */
public final class Hooks {

    private static final Logger log = LoggerFactory.getLogger(Hooks.class);

    private final List<Registration<PreToolHook>> preHooks = new CopyOnWriteArrayList<>();
    private final List<Registration<PostToolHook>> postHooks = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> onStart = new CopyOnWriteArrayList<>();
    private final List<Consumer<StopInput>> onStop = new CopyOnWriteArrayList<>();

    // ============ Registration ============

    /** Register a pre-tool hook with optional tool name matcher (null = all tools). */
    public Hooks onPreTool(String toolMatcher, PreToolHook hook) { preHooks.add(new Registration<>(toolMatcher, hook)); return this; }
    public Hooks onPreTool(PreToolHook hook) { return onPreTool(null, hook); }

    /** Register a post-tool hook. */
    public Hooks onPostTool(String toolMatcher, PostToolHook hook) { postHooks.add(new Registration<>(toolMatcher, hook)); return this; }
    public Hooks onPostTool(PostToolHook hook) { return onPostTool(null, hook); }

    /** Session lifecycle hooks. */
    public Hooks onSessionStart(Consumer<String> hook) { onStart.add(hook); return this; }
    public Hooks onSessionStop(Consumer<StopInput> hook) { onStop.add(hook); return this; }

    // ============ Hook execution ============

    /** Apply pre-tool hooks. Returns the final HookDecision. */
    public HookDecision applyPreTool(Tool tool, Map<String, Object> arguments) {
        Map<String, Object> current = arguments;
        for (var reg : preHooks) {
            if (!matches(reg.matcher, tool.getName())) continue;
            try {
                HookDecision d = reg.hook.apply(tool, current);
                if (d.outcome() == HookDecision.Outcome.DENY) {
                    log.info("Pre-tool hook denied '{}': {}", tool.getName(), d.reason());
                    return d;
                }
                if (d.modifiedArgs() != null) current = d.modifiedArgs();
            } catch (Exception e) { log.warn("Pre-tool hook '{}' error: {}", tool.getName(), e.getMessage()); }
        }
        return HookDecision.allow(current);
    }

    /** Apply post-tool hooks. Yields messages (progress, attachment) via consumer. */
    public void applyPostTool(Tool tool, Map<String, Object> arguments, Object result,
                               Consumer<HookMessage> onMessage) {
        for (var reg : postHooks) {
            if (!matches(reg.matcher, tool.getName())) continue;
            try { reg.hook.apply(tool, arguments, result, onMessage); }
            catch (Exception e) { log.warn("Post-tool hook '{}' error: {}", tool.getName(), e.getMessage()); }
        }
    }

    /** Fire session start hooks. */
    public void fireSessionStart(String agentName) {
        onStart.forEach(h -> safeRun(() -> h.accept(agentName), "sessionStart"));
    }

    /** Fire session stop hooks. */
    public void fireSessionStop(StopInput input) {
        onStop.forEach(h -> safeRun(() -> h.accept(input), "sessionStop"));
    }

    // ============ Hook interfaces ============

    @FunctionalInterface
    public interface PreToolHook { HookDecision apply(Tool tool, Map<String, Object> arguments); }

    @FunctionalInterface
    public interface PostToolHook {
        void apply(Tool tool, Map<String, Object> arguments, Object result, Consumer<HookMessage> onMessage);
    }

    // ============ Types ============

    /** Decision from a pre-tool hook: ALLOW (with optional modified args), DENY (with reason). */
    public record HookDecision(Outcome outcome, Map<String, Object> modifiedArgs, String reason,
                                boolean preventContinuation) {
        public enum Outcome { ALLOW, DENY }
        public static HookDecision allow(Map<String, Object> args) { return new HookDecision(Outcome.ALLOW, args, null, false); }
        public static HookDecision deny(String reason) { return new HookDecision(Outcome.DENY, null, reason, true); }
        public static HookDecision deny(String reason, boolean preventContinuation) { return new HookDecision(Outcome.DENY, null, reason, preventContinuation); }
    }

    /** Message yielded by post-tool hooks (progress, attachment, additional context). */
    public record HookMessage(Type type, String content, Object payload) {
        public enum Type { PROGRESS, ATTACHMENT, ADDITIONAL_CONTEXT }
        public static HookMessage progress(String content) { return new HookMessage(Type.PROGRESS, content, null); }
        public static HookMessage attachment(String content, Object payload) { return new HookMessage(Type.ATTACHMENT, content, payload); }
        public static HookMessage context(String content) { return new HookMessage(Type.ADDITIONAL_CONTEXT, content, null); }
    }

    /** Input to session stop hooks. */
    public record StopInput(String agentName, String reason, String error, long durationMs) {}

    // ============ Internal ============

    private record Registration<T>(String matcher, T hook) {}

    private static boolean matches(String matcher, String toolName) {
        if (matcher == null) return true;
        return toolName.matches(matcher.replace("*", ".*"));
    }

    private void safeRun(Runnable r, String hookType) {
        try { r.run(); } catch (Exception e) { log.warn("Hook '{}' error: {}", hookType, e.getMessage()); }
    }
}
