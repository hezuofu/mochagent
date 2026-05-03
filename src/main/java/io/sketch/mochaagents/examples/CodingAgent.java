package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.impl.BaseAgent;
import io.sketch.mochaagents.agent.impl.CodeAgent;
import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.provider.MockLLM;
import io.sketch.mochaagents.tool.ToolRegistry;

import java.util.concurrent.CompletableFuture;

/**
 * Coding Agent — uses CodeAgent internally for code generation tasks.
 * Demonstrates how to create a specialized agent by composing framework primitives.
 * @author lanxia39@163.com
 */
public class CodingAgent extends BaseAgent<String, String> {

    private final CodeAgent delegate;

    protected CodingAgent(Builder builder) {
        super(builder);
        LLM llm = builder.llm != null ? builder.llm : MockLLM.create();
        ToolRegistry tools = builder.toolRegistry != null ? builder.toolRegistry : new ToolRegistry();
        this.delegate = CodeAgent.builder().name(name).llm(llm)
                .toolRegistry(tools).maxSteps(15).build();
    }

    @Override
    protected String doExecute(String input, AgentContext ctx) {
        return delegate.execute(input, ctx);
    }

    @Override
    public CompletableFuture<String> executeAsync(String input, AgentContext ctx) {
        return delegate.executeAsync(input, ctx);
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder extends BaseAgent.Builder<String, String, Builder> {
        LLM llm;
        ToolRegistry toolRegistry;

        public Builder llm(LLM llm) { this.llm = llm; return this; }
        public Builder toolRegistry(ToolRegistry r) { this.toolRegistry = r; return this; }

        @Override
        public CodingAgent build() { return new CodingAgent(this); }
    }
}
