package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.impl.BaseAgent;
import io.sketch.mochaagents.agent.impl.CompositeAgent;
import io.sketch.mochaagents.agent.impl.CodeAgent;
import io.sketch.mochaagents.agent.impl.ToolCallingAgent;
import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.provider.MockLLM;
import io.sketch.mochaagents.tool.ToolRegistry;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Full-Stack Agent — composites Coding + Review + DevOps agents.
 * Demonstrates the CompositeAgent pattern for multi-agent pipelines.
 * @author lanxia39@163.com
 */
public class FullStackAgent extends BaseAgent<String, String> {

    private final CompositeAgent<String, String> delegate;

    protected FullStackAgent(Builder builder) {
        super(builder);
        LLM llm = builder.llm != null ? builder.llm : MockLLM.create();
        ToolRegistry tools = builder.toolRegistry != null ? builder.toolRegistry : new ToolRegistry();

        var coder = CodeAgent.builder().name("coder").llm(llm)
                .toolRegistry(tools).maxSteps(10).build();
        var reviewer = ToolCallingAgent.builder().name("reviewer").llm(llm)
                .toolRegistry(tools).maxSteps(8).build();
        var devops = ToolCallingAgent.builder().name("devops").llm(llm)
                .toolRegistry(tools).maxSteps(12).build();

        this.delegate = CompositeAgent.of(coder, reviewer, devops);
    }

    @Override
    protected String doExecute(String input, AgentContext ctx) {
        List<String> results = delegate.execute(input, ctx);
        return String.join("\n", results);
    }

    @Override
    public CompletableFuture<String> executeAsync(String input, AgentContext ctx) {
        return delegate.executeAsync(input, ctx).thenApply(r -> String.join("\n", r));
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder extends BaseAgent.Builder<String, String, Builder> {
        LLM llm;
        ToolRegistry toolRegistry;

        public Builder llm(LLM llm) { this.llm = llm; return this; }
        public Builder toolRegistry(ToolRegistry r) { this.toolRegistry = r; return this; }

        @Override
        public FullStackAgent build() { return new FullStackAgent(this); }
    }
}
