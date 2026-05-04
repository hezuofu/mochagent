package io.sketch.mochaagents.agent.react;

import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class HooksTest {

    private static Tool dummy(String name) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Map<String, ToolInput> getInputs() { return Map.of(); }
            @Override public String getOutputType() { return "string"; }
            @Override public Object call(Map<String, Object> args) { return "ok"; }
            @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.LOW; }
        };
    }

    @Test void preHookAllows() {
        Hooks h = new Hooks().onPreTool((t, a) -> Hooks.HookDecision.allow(a));
        var r = h.applyPreTool(dummy("test"), Map.of());
        assertEquals(Hooks.HookDecision.Outcome.ALLOW, r.outcome());
    }

    @Test void preHookDenies() {
        Hooks h = new Hooks().onPreTool((t, a) -> Hooks.HookDecision.deny("blocked"));
        var r = h.applyPreTool(dummy("test"), Map.of());
        assertEquals(Hooks.HookDecision.Outcome.DENY, r.outcome());
        assertEquals("blocked", r.reason());
    }

    @Test void preHookCanModifyArgs() {
        Hooks h = new Hooks().onPreTool((t, a) -> Hooks.HookDecision.allow(Map.of("key", "modified")));
        var r = h.applyPreTool(dummy("test"), Map.of());
        assertEquals("modified", r.modifiedArgs().get("key"));
    }

    @Test void postHookCalled() {
        Hooks h = new Hooks();
        AtomicInteger count = new AtomicInteger();
        h.onPostTool((t, a, r, m) -> count.incrementAndGet());
        h.applyPostTool(dummy("test"), Map.of(), "result", msg -> {});
        assertEquals(1, count.get());
    }

    @Test void toolMatcherFilters() {
        Hooks h = new Hooks().onPreTool("echo", (t, a) -> Hooks.HookDecision.deny("blocked"));
        var r = h.applyPreTool(dummy("grep"), Map.of());
        assertEquals(Hooks.HookDecision.Outcome.ALLOW, r.outcome());
        r = h.applyPreTool(dummy("echo"), Map.of());
        assertEquals(Hooks.HookDecision.Outcome.DENY, r.outcome());
    }

    @Test void sessionStartStopFire() {
        Hooks h = new Hooks();
        AtomicInteger starts = new AtomicInteger(), stops = new AtomicInteger();
        h.onSessionStart(s -> starts.incrementAndGet());
        h.onSessionStop(i -> stops.incrementAndGet());
        h.fireSessionStart("agent1");
        h.fireSessionStop(new Hooks.StopInput("agent1", "completed", null, 100));
        assertEquals(1, starts.get());
        assertEquals(1, stops.get());
    }
}
