package io.sketch.mochaagents.agent.loop;

import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.context.ContextManager;
import io.sketch.mochaagents.evaluation.EvaluationResult;
import io.sketch.mochaagents.memory.AgentMemory;
import io.sketch.mochaagents.reasoning.ReasoningChain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Composable execution pipeline — pre→loop→post stages.
 * <p>Extracted from ReActAgent to keep run() concise and testable.
 * @param <I> input type
 * @param <O> output type
 * @author lanxia39@163.com
 */
public class AgentPipeline<I, O> {

    private final List<Stage<I, O>> preStages = new ArrayList<>();
    private final List<Stage<I, O>> postStages = new ArrayList<>();
    private Consumer<ContextManager> compressor = ctx -> {};

    /** Add a pre-loop stage (runs before ReAct loop, in order). */
    public AgentPipeline<I, O> pre(Stage<I, O> s) { preStages.add(s); return this; }

    /** Add a post-loop stage (runs after ReAct loop, in order). */
    public AgentPipeline<I, O> post(Stage<I, O> s) { postStages.add(s); return this; }

    /** Set context compressor for post-loop cleanup. */
    public AgentPipeline<I, O> compress(Consumer<ContextManager> c) { this.compressor = c; return this; }

    /** Run all pre-stages. */
    public void runPre(I input, AgentContext ctx, AgentMemory memory, ContextManager ctxMgr,
                        List<BiConsumer<String, Object>> collectors) {
        for (Stage<I, O> s : preStages) s.execute(input, ctx, memory, ctxMgr, collectors);
    }

    /** Run all post-stages. */
    public void runPost(I input, O output, AgentContext ctx, AgentMemory memory,
                         ContextManager ctxMgr, List<BiConsumer<String, Object>> collectors) {
        for (Stage<I, O> s : postStages) s.execute(input, ctx, memory, ctxMgr, collectors);
        compressor.accept(ctxMgr);
    }

    /** A single pipeline stage — receives input, ctx, memory, and output collectors. */
    @FunctionalInterface
    public interface Stage<I, O> {
        void execute(I input, AgentContext ctx, AgentMemory memory, ContextManager ctxMgr,
                     List<BiConsumer<String, Object>> collectors);
    }
}
