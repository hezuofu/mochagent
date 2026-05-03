package io.sketch.mochaagents.agent.react;

import io.sketch.mochaagents.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.*;

/**
 * Hook system — pre/post tool hooks, session lifecycle hooks.
 * Pattern from claude-code's hook system (toolHooks, stopHooks, sessionHooks).
 *
 * <p>Usage:
 * <pre>
 * hooks.onPreTool((tool, args) -> {
 *     if ("rm".equals(tool.getName())) return HookResult.deny("rm blocked");
 *     return HookResult.allow(args);
 * });
 * </pre>
 * @author lanxia39@163.com
 */
public final class Hooks {

    private static final Logger log = LoggerFactory.getLogger(Hooks.class);

    // Pre-tool hooks: can modify arguments or deny execution
    private final List<PreToolHook> preToolHooks = new CopyOnWriteArrayList<>();

    // Post-tool hooks: can observe/modify results
    private final List<PostToolHook> postToolHooks = new CopyOnWriteArrayList<>();

    // Session lifecycle
    private final List<Consumer<String>> onStart = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> onStop = new CopyOnWriteArrayList<>();

    // ============ Registration ============

    /** Register a pre-tool hook. Return ALLOW(args) to proceed, DENY(reason) to block. */
    public Hooks onPreTool(PreToolHook hook) { preToolHooks.add(hook); return this; }

    /** Register a post-tool hook. Receives tool, args, result. */
    public Hooks onPostTool(PostToolHook hook) { postToolHooks.add(hook); return this; }

    /** Register a session start hook. Receives agent name. */
    public Hooks onSessionStart(Consumer<String> hook) { onStart.add(hook); return this; }

    /** Register a session stop hook. Receives agent name. */
    public Hooks onSessionStop(Consumer<String> hook) { onStop.add(hook); return this; }

    // ============ Internal: apply hooks ============

    /** Apply all pre-tool hooks. Returns ALLOW(args) if all pass, DENY(reason) if any block. */
    HookResult applyPreTool(Tool tool, Map<String, Object> arguments) {
        Map<String, Object> current = arguments;
        for (PreToolHook h : preToolHooks) {
            try {
                HookResult r = h.apply(tool, current);
                if (r.blocked()) { log.info("Pre-tool hook blocked '{}': {}", tool.getName(), r.reason()); return r; }
                if (r.modifiedArgs() != null) current = r.modifiedArgs();
            } catch (Exception e) { log.warn("Pre-tool hook error: {}", e.getMessage()); }
        }
        return HookResult.allow(current);
    }

    /** Apply all post-tool hooks. */
    void applyPostTool(Tool tool, Map<String, Object> arguments, Object result) {
        for (PostToolHook h : postToolHooks) {
            try { h.accept(tool, arguments, result); }
            catch (Exception e) { log.warn("Post-tool hook error: {}", e.getMessage()); }
        }
    }

    /** Fire session start hooks. */
    void fireSessionStart(String agentName) { onStart.forEach(h -> { try { h.accept(agentName); } catch (Exception e) { log.warn("Session start hook error", e); } }); }

    /** Fire session stop hooks. */
    void fireSessionStop(String agentName) { onStop.forEach(h -> { try { h.accept(agentName); } catch (Exception e) { log.warn("Session stop hook error", e); } }); }

    // ============ Hook interfaces ============

    /** Pre-tool hook — can modify arguments or deny execution. */
    @FunctionalInterface
    public interface PreToolHook {
        HookResult apply(Tool tool, Map<String, Object> arguments);
    }

    /** Post-tool hook — receives tool, original arguments, and result. */
    @FunctionalInterface
    public interface PostToolHook {
        void accept(Tool tool, Map<String, Object> arguments, Object result);
    }

    // ============ Hook result ============

    /** Result from a pre-tool hook: ALLOW with optional modified args, or DENY with reason. */
    public record HookResult(Map<String, Object> modifiedArgs, String reason) {
        public boolean blocked() { return reason != null; }
        public static HookResult allow(Map<String, Object> args) { return new HookResult(args, null); }
        public static HookResult deny(String reason) { return new HookResult(null, reason); }
    }
}
