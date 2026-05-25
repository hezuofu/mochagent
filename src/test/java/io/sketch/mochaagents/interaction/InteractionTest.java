// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.interaction;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InteractionTest {

    // ── SafetyLine ──

    @Test void hardBlocksDangerousCommands() {
        var d = SafetyLine.hardBlock(new ToolUse("bash", Map.of("command", "rm -rf /")));
        assertTrue(d instanceof Decision.HardDeny);
    }

    @Test void allowsSafeCommands() {
        var d = SafetyLine.hardBlock(new ToolUse("bash", Map.of("command", "ls -la")));
        assertTrue(d instanceof Decision.Allow);
    }

    @Test void nonShellToolsBypassSafetyLine() {
        var d = SafetyLine.hardBlock(new ToolUse("file_read", Map.of("filePath", "/etc/passwd")));
        assertTrue(d instanceof Decision.Allow);
    }

    // ── PermissionRules ──

    @Test void denyRuleBlocks() {
        var rules = new PermissionRules().add("rm*", PermissionRules.Behavior.DENY, PermissionRules.Source.POLICY);
        assertEquals(PermissionRules.Behavior.DENY, rules.resolve("rm"));
        assertEquals(PermissionRules.Behavior.DENY, rules.resolve("rmdir"));
    }

    @Test void allowRuleOverridesDefaultAsk() {
        var rules = new PermissionRules().add("echo", PermissionRules.Behavior.ALLOW, PermissionRules.Source.USER);
        assertEquals(PermissionRules.Behavior.ALLOW, rules.resolve("echo"));
    }

    @Test void higherSourceWins() {
        var rules = new PermissionRules()
                .add("bash", PermissionRules.Behavior.ALLOW, PermissionRules.Source.USER)
                .add("bash", PermissionRules.Behavior.DENY, PermissionRules.Source.POLICY);
        assertEquals(PermissionRules.Behavior.DENY, rules.resolve("bash"));
    }

    @Test void defaultBehaviorWhenNoMatch() {
        var rules = new PermissionRules().defaultBehavior(PermissionRules.Behavior.ASK);
        assertEquals(PermissionRules.Behavior.ASK, rules.resolve("unknown_tool"));
    }

    @Test void contentSpecificMatch() {
        var rules = new PermissionRules()
                .add("bash", "npm publish", PermissionRules.Behavior.DENY, PermissionRules.Source.POLICY, "no publish");
        var deny = rules.matchDeny("bash", "npm publish --access public");
        assertNotNull(deny);
        assertEquals("no publish", deny.reason());
    }

    // ── Session ──

    @Test void sessionTracksDenials() {
        var s = new Session("s1").maxDenialsBeforeBlock(2);
        assertFalse(s.recordDenial("bash"));
        assertTrue(s.recordDenial("bash"));
    }

    @Test void sessionAllowList() {
        var s = new Session("s1");
        s.allowSession("bash");
        assertTrue(s.isSessionAllowed("bash"));
        assertFalse(s.isSessionAllowed("rm"));
    }

    // ── ApprovalBroker ──

    @Test void brokerRacesHandlers() throws Exception {
        var b = new ApprovalBroker();
        b.register((use, sid) -> CompletableFuture.completedFuture(Decision.allow("ok")));
        var d = b.request(new ToolUse("echo", Map.of()), "s1").get();
        assertTrue(d instanceof Decision.Allow);
    }

    @Test void brokerFirstResponseWins() throws Exception {
        var b = new ApprovalBroker();
        b.register((use, sid) -> {
            var f = new CompletableFuture<Decision>();
            new Thread(() -> { try { Thread.sleep(1000); f.complete(Decision.allow("slow")); } catch (Exception e) {} }).start();
            return f;
        });
        b.register((use, sid) -> CompletableFuture.completedFuture(Decision.allow("fast")));
        var d = b.request(new ToolUse("echo", Map.of()), "s1").get();
        assertEquals("fast", ((Decision.Allow) d).reason());
    }

    // ── DecisionPipeline ──

    @Test void pipelineHardDeny() {
        var pipeline = DecisionPipeline.standard();
        var rules = new PermissionRules();
        var use = new ToolUse("bash", Map.of("command", "rm -rf /"));
        assertTrue(pipeline.evaluate(use, rules) instanceof Decision.HardDeny);
    }

    @Test void pipelineAllowFromRules() {
        var pipeline = DecisionPipeline.standard();
        var rules = new PermissionRules()
                .add("echo", PermissionRules.Behavior.ALLOW, PermissionRules.Source.USER);
        var d = pipeline.evaluate(new ToolUse("echo", Map.of()), rules);
        assertTrue(d instanceof Decision.Allow);
    }

    // ── InterruptSignal ──

    @Test void interruptAndClear() {
        InterruptSignal.fire("s1", InterruptSignal.Reason.USER_REQUEST);
        assertTrue(InterruptSignal.isInterrupted("s1"));
        InterruptSignal.clear("s1");
        assertFalse(InterruptSignal.isInterrupted("s1"));
    }

    @Test void interruptIsSessionScoped() {
        InterruptSignal.fire("s1", InterruptSignal.Reason.USER_REQUEST);
        assertFalse(InterruptSignal.isInterrupted("s2"));
        InterruptSignal.clear("s1");
    }
}
