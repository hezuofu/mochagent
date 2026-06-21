// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.learn;

import io.sketch.mochaagents.memory.MemoryManager;

import java.util.List;
import java.util.Map;

/**
 * Composable self-learning pipeline — GenericAgent pattern.
 *
 * <p>Plugs into the agent loop via {@link #configure(MemoryManager)}:
 * <pre>{@code
 * LearningLoop loop = LearningLoop.configure(memory)
 *     .withWorkingMemory()
 *     .withGlobalMemory()
 *     .withAntiForgetting(10, 65)
 *     .withSummaryRequired();
 * }</pre>
  * @author lanxia39@163.com
 */
public final class LearningLoop {

    private final MemoryManager memory;
    private SummaryExtractor summaryExtractor = SummaryExtractor.fromTags();
    private TurnHook turnHook = TurnHook.noop();
    private TurnHook dangerHook = TurnHook.noop();
    private SettlementHook settlementHook = SettlementHook.noop();
    private int globalMemoryInterval;
    private int dangerInterval;
    private int forceInterruptAt;

    private LearningLoop(MemoryManager memory) { this.memory = memory; }

    public static LearningLoop configure(MemoryManager memory) {
        return new LearningLoop(memory);
    }

    // ── Builder ──
   // MemoryManager already has it
    public LearningLoop withWorkingMemory() { return this; }

    public LearningLoop withGlobalMemory() {
        memory.withGlobalMemory(); return this;
    }

    public LearningLoop withAntiForgetting(int globalInterval, int forceInterruptAt) {
        this.globalMemoryInterval = globalInterval;
        this.forceInterruptAt = forceInterruptAt;
        this.dangerInterval = Math.max(1, globalInterval / 2);
        this.dangerHook = (ctx) -> {
            if (ctx.turn() % forceInterruptAt == 0) {
                return "\n[DANGER] Turn " + ctx.turn() + ". Must ask_user with summary, no more retries.";
            }
            if (ctx.turn() % dangerInterval == 0) {
                return "\n[DANGER] Turn " + ctx.turn() + ". No pointless retries. Switch strategy or ask user.";
            }
            return "";
        };
        return this;
    }

    public LearningLoop withSummaryRequired() {
        this.summaryExtractor = SummaryExtractor.fromTags();
        this.turnHook = (ctx) -> {
            if (!ctx.response().contains("<summary>") && !ctx.response().contains("</summary>")) {
                return "\n[SYSTEM] Must include <summary> in response!";
            }
            return "";
        };
        return this;
    }

    public LearningLoop withSettlement() {
        this.settlementHook = (ctx) -> memory.settle(ctx.response());
        return this;
    }

    // ── Hooks called from agent loop ──

    /** Call after each turn. Returns text to inject into next prompt. */
    public String afterTurn(int turn, String response, List<Map<String, Object>> toolCalls) {
        TurnContext ctx = new TurnContext(turn, response, toolCalls, memory);
        StringBuilder injection = new StringBuilder();

        // 1. Extract summary + record in history
        String summary = summaryExtractor.extract(response);
        if (!summary.isEmpty()) {
            injection.append("\n[Summary: ").append(summary).append("]");
        }

        // 2. Turn hook (summary enforcement)
        String th = turnHook.afterTurn(ctx);
        if (!th.isEmpty()) {
            injection.append(th);
        }

        // 3. Periodic global memory reinjection
        if (globalMemoryInterval > 0 && turn % globalMemoryInterval == 0) {
            String gc = memory.globalContext();
            if (!gc.isEmpty()) {
                injection.append(gc);
            }
        }

        // 4. Danger warnings
        String dw = dangerHook.afterTurn(ctx);
        if (!dw.isEmpty()) {
            injection.append(dw);
        }

        // 5. Working memory context
        injection.append(memory.workingContext());

        return injection.toString();
    }

    /** Call when task completes to trigger settlement. */
    public void onTaskComplete(String taskResult) {
        settlementHook.onComplete(new TurnContext(0, taskResult, List.of(), memory));
    }

    // ── Functional interfaces ──

    @FunctionalInterface
    public interface SummaryExtractor {
        String extract(String response);
        static SummaryExtractor fromTags() {
            return r -> {
                int s = r.indexOf("<summary>"), e = r.indexOf("</summary>");
                return s >= 0 && e > s ? r.substring(s + 9, e).trim() : "";
            };
        }
    }

    @FunctionalInterface
    public interface TurnHook {
        String afterTurn(TurnContext ctx);
        static TurnHook noop() { return ctx -> ""; }
    }

    @FunctionalInterface
    public interface SettlementHook {
        void onComplete(TurnContext ctx);
        static SettlementHook noop() { return ctx -> {}; }
    }

    /** Context passed to each hook. */
    public record TurnContext(int turn, String response, List<Map<String, Object>> toolCalls, MemoryManager memory) {}
}
